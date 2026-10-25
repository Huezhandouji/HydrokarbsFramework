package com.hydrokarbs.bridge.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.World;

import com.hydrokarbs.engine.port.ResetStrategy;
import com.hydrokarbs.engine.port.WorldHandle;
import com.hydrokarbs.engine.port.WorldPort;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.map.MapDefinition;

/**
 * 世界端口实现:模板复制/引用计数/副本处置(03-会话与模式切换 §3.7;施工计划 2.4 步骤 5;
 * 12-代码骨架 §12.1 {@code bridge/world/} 落位)。
 *
 * <p><b>规则表逐行落点</b>:调用方只有会话内核(获取在切换准备阶段、释放在切换完成/解散);
 * 同一 (map, session) 重复获取幂等返回同句柄并累加计数,并发获取合并到同一次复制;
 * 归零按句柄的 {@link ResetStrategy} 处置副本;复制源恒为模板目录,
 * {@code REUSE_DIRTY} 的复用只发生在同会话(副本目录名含会话短码,天然不跨会话)。
 *
 * <p><b>两段线程纪律</b>(§3.7 接口注释 + §16.4 三律②):复制/删目录 = 注入的 IO 执行器
 * (生产 = 虚拟线程);{@code createWorld}/{@code unloadWorld} = 注入的主线程执行器
 * (生产 = Threads 回程口)——两个执行器均构造注入,线程契约因此离线可断言(★17)。
 *
 * <p><b>安全边界</b>:副本目录的创建/清空/删除全部限定在「副本根下、文件名恰为
 * {@code <mapId>-<会话短码>}」的路径形态内,删前断言路径形态——绝不递归删除任意路径。
 *
 * <p><b>超时即失败,不降级</b>:整体 {@code orTimeout(默认 30s)}(§3.5 防护④);
 * 超时/失败/缺模板 → future 异常完成 + 清 in-flight,计数未入表;超时后迟到的复制
 * 流水走到加载点会被回滚(不卸入表),不产生无人持有的副本。
 */
public final class WorldPortImpl implements WorldPort {

    /** 引用计数键:(地图 id, 会话)。 */
    private record CountKey(String mapId, SessionId session) {
    }

    /** 引用计数条目:计数 + 已发放句柄(handle 只在入表时赋定)。 */
    private static final class RefEntry {

        final AtomicInteger count = new AtomicInteger();
        final WorldHandle handle;

        RefEntry(WorldHandle handle) {
            this.handle = handle;
        }
    }

    private final WorldLoader worldLoader;
    private final Path templateRoot;
    private final Path copyRoot;
    private final Executor mainExecutor;
    private final Executor ioExecutor;
    private final long prepareTimeoutMillis;
    private final Logger log;

    /** 引用计数表((map, session) → 条目);写点收敛在获取完成/释放归零,主线程单线程访问。 */
    private final ConcurrentHashMap<CountKey, RefEntry> table = new ConcurrentHashMap<>();

    /** 进行中的获取(同 key 并发 miss 合并:后到者等待同一 future)。 */
    private final ConcurrentHashMap<CountKey, CompletableFuture<WorldHandle>> inFlight =
            new ConcurrentHashMap<>();

    /**
     * @param worldLoader          主线程世界装卸缝(生产 {@link BukkitWorldLoader})
     * @param templateRoot         模板根(数据目录 {@code maps/templates/},§15.2)
     * @param copyRoot             副本根(生产 = Bukkit 世界容器目录;测试 = 临时目录)
     * @param mainExecutor         主线程执行器(生产 = Threads 回程口;测试 = 直跑)
     * @param ioExecutor           IO 执行器(生产 = 虚拟线程;测试 = 直跑/可控门闩)
     * @param prepareTimeout       获取整体超时(§3.5 防护④,生产 30s;测试注入短超时)
     * @param log                  日志
     */
    public WorldPortImpl(WorldLoader worldLoader, Path templateRoot, Path copyRoot,
                         Executor mainExecutor, Executor ioExecutor,
                         Duration prepareTimeout, Logger log) {
        this.worldLoader = java.util.Objects.requireNonNull(worldLoader, "worldLoader");
        this.templateRoot = java.util.Objects.requireNonNull(templateRoot, "templateRoot");
        this.copyRoot = java.util.Objects.requireNonNull(copyRoot, "copyRoot");
        this.mainExecutor = java.util.Objects.requireNonNull(mainExecutor, "mainExecutor");
        this.ioExecutor = java.util.Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.prepareTimeoutMillis = java.util.Objects.requireNonNull(prepareTimeout, "prepareTimeout")
                .toMillis();
        this.log = java.util.Objects.requireNonNull(log, "log");
    }

    // ── 获取:七步流水 ───────────────────────────────────────────

    @Override
    public CompletableFuture<WorldHandle> acquire(MapDefinition map, SessionId forSession) {
        java.util.Objects.requireNonNull(map, "map");
        java.util.Objects.requireNonNull(forSession, "forSession");
        CountKey key = new CountKey(map.id(), forSession);
        // ① 主线程入口查表:命中 → +1 幂等返回同句柄(切换到同图同策略零复制成本)
        RefEntry existing = table.get(key);
        if (existing != null) {
            existing.count.incrementAndGet();
            return CompletableFuture.completedFuture(existing.handle);
        }
        // ② 挂 in-flight:同 key 并发 miss 时,后到者直接等待同一 future(只复制一次);
        //    等待者在 future 完成回调里各自 +1(引用数 = 获取次数,后述 bump)
        CompletableFuture<WorldHandle> pending = new CompletableFuture<>();
        CompletableFuture<WorldHandle> raced = inFlight.putIfAbsent(key, pending);
        if (raced != null) {
            return raced.thenApply(handle -> {
                bumpAfterMerge(key);
                return handle;
            });
        }
        // 失败/超时统一清理钩子:future 以任何异常完成 → 摘 in-flight,
        // 否则该 key 的后续获取会永远等一个已死的 future
        pending.whenComplete((handle, err) -> {
            if (err != null) {
                inFlight.remove(key, pending);
            }
        });
        // ③~⑦ 复制 → 回主线程加载 → 入表发布(整体超时见返回处 orTimeout)
        prepare(key, map, forSession, pending);
        return pending.orTimeout(prepareTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** ③~⑦:IO 复制(先清残留/复用)→ 主线程加载 → 入表 +1 → 清 in-flight → 完成。 */
    private void prepare(CountKey key, MapDefinition map, SessionId session,
                         CompletableFuture<WorldHandle> pending) {
        String instanceName = instanceNameOf(map, session);
        Path templateDir = templateRoot.resolve(map.id());
        if (!Files.isDirectory(templateDir)) {
            // 缺模板目录 → 同一失败路径(§3.7 规则表「模板」行):异常完成、未入表
            pending.completeExceptionally(new IllegalStateException(
                    "地图模板目录缺失: " + templateDir + "(地图 " + map.id() + ")"));
            return;
        }
        ioExecutor.execute(() -> {
            try {
                // ③ 复制模板目录 → 副本目录(REUSE_DIRTY 同会话复载跳过复制;残留先清)
                prepareCopyDir(map, session, templateDir, instanceName);
                // ④ Threads 回主线程
                mainExecutor.execute(() -> loadAndPublish(key, map, instanceName, pending));
            } catch (Throwable t) {
                pending.completeExceptionally(t);
            }
        });
    }

    /** ⑤~⑦(主线程):加载世界 → 包装句柄 → 入表 +1 → 清 in-flight → 完成 future。 */
    private void loadAndPublish(CountKey key, MapDefinition map, String instanceName,
                                CompletableFuture<WorldHandle> pending) {
        try {
            // 迟到守卫:future 已被超时抢先异常完成 → 不加载不入表(后续获取可正常重走)
            if (pending.isCompletedExceptionally()) {
                return;
            }
            // ⑤ createWorld(只在 WorldLoader 内,主线程执行器上下文)
            World world = worldLoader.createWorld(instanceName, copyRoot.resolve(instanceName));
            if (world == null) {
                throw new IllegalStateException("世界加载返回空: " + instanceName);
            }
            WorldHandle handle = new WorldHandle(world, instanceName, map, map.resetStrategy());
            // ⑥ 入表 +1(先到者的引用)、清 in-flight
            RefEntry entry = new RefEntry(handle);
            entry.count.set(1);
            table.put(key, entry);
            inFlight.remove(key, pending);
            // ⑦ 完成 future;complete 失败 = 超时恰在入表后抢先 → 回滚本次发放,不留无主句柄
            if (!pending.complete(handle)) {
                table.remove(key, entry);
                unloadQuietly(handle, "获取超时与完成竞争,回滚刚加载的副本: ");
            }
        } catch (Throwable t) {
            pending.completeExceptionally(t);
        }
    }

    /** 并发合并的后到者在 future 完成回调里补计数(引用数 = 获取次数)。 */
    private void bumpAfterMerge(CountKey key) {
        RefEntry entry = table.get(key);
        if (entry != null) {
            entry.count.incrementAndGet();
        }
        // 查不到 = 条目已归零移除(极端时序窗口):句柄已处置,
        // 该次获取的释放会走「未知句柄」幂等路径,无害
    }

    // ── 释放:六步流水 ───────────────────────────────────────────

    @Override
    public CompletableFuture<Void> release(WorldHandle handle) {
        if (handle == null) {
            return CompletableFuture.completedFuture(null);
        }
        // ① 未知句柄或计数已零(归零条目已移除)→ 直接完成:幂等,重复释放无害
        CountKey key = null;
        for (Map.Entry<CountKey, RefEntry> row : table.entrySet()) {
            if (row.getValue().handle == handle) {     // 引用相等:record 值等价的两次发放不是同一引用
                key = row.getKey();
                break;
            }
        }
        if (key == null) {
            return CompletableFuture.completedFuture(null);
        }
        RefEntry entry = table.get(key);
        if (entry == null) {
            return CompletableFuture.completedFuture(null);
        }
        // ② 计数 -1;③ 大于零 → 立即完成(未归零即无处置,调用方无需区分两种完成)
        if (entry.count.decrementAndGet() > 0) {
            return CompletableFuture.completedFuture(null);
        }
        // ④ 归零:先移除条目(不变式:归零必移除),回主线程卸载
        table.remove(key, entry);
        String shortCode = key.session().shortCode();
        String mapId = key.mapId();
        CompletableFuture<Void> done = new CompletableFuture<>();
        mainExecutor.execute(() -> disposeAfterZero(handle, mapId, shortCode, done));
        return done;
    }

    /** ④~⑥(主线程):卸载(不存档)→ 按策略处置副本目录;卸载失败 SEVERE + 保留目录。 */
    private void disposeAfterZero(WorldHandle handle, String mapId, String shortCode,
                                  CompletableFuture<Void> done) {
        boolean unloaded;
        try {
            // 卸载不存档:副本是临时实例,内容处置归 ResetStrategy(模板才是母本)
            unloaded = worldLoader.unloadWorld(handle.world(), false);
        } catch (Throwable t) {
            log.log(Level.SEVERE, "卸载世界副本异常(保留目录,不阻塞调用方): "
                    + handle.instanceName(), t);
            unloaded = false;
        }
        if (!unloaded) {
            // ⑥ 卸载失败:SEVERE + 保留目录(Bukkit 仍持引用,删目录会撞下次加载)
            log.log(Level.SEVERE, "世界副本卸载失败,保留目录待下轮处置: {0}",
                    handle.instanceName());
            done.complete(null);
            return;
        }
        // ⑤ 按策略分派:REUSE_DIRTY 保留副本目录(同会话再次获取直接复载该目录,
        // 目录名含会话短码,天然不会跨会话混用);TEMPLATE_RESTORE 删除目录(IO 线程)
        if (handle.reset() == ResetStrategy.TEMPLATE_RESTORE) {
            ioExecutor.execute(() -> {
                try {
                    deleteCopyDir(mapId, shortCode);
                } catch (Throwable t) {
                    log.log(Level.WARNING, "删除副本目录失败(残留由下轮获取的清残留兜底): "
                            + mapId + "-" + shortCode, t);
                } finally {
                    done.complete(null);       // 归零处置完成后才完成
                }
            });
        } else {
            done.complete(null);
        }
    }

    // ── 副本目录的复制与处置(安全边界在此) ─────────────────────────

    /** 副本目录名 = 地图 id + "-" + 会话短码(§3.7 接口注释;2.1 shortCode 文件名安全化)。 */
    private static String instanceNameOf(MapDefinition map, SessionId session) {
        return map.id() + "-" + session.shortCode();
    }

    /**
     * 复制前的副本目录整备:目录已存在时,REUSE_DIRTY 直接复用(同会话复载,跳过复制);
     * TEMPLATE_RESTORE 视为异常残留,形态断言后清空再复制。
     */
    private void prepareCopyDir(MapDefinition map, SessionId session, Path templateDir,
                                String instanceName) throws IOException {
        Path copyDir = copyRoot.resolve(instanceName);
        if (Files.isDirectory(copyDir)) {
            if (map.resetStrategy() == ResetStrategy.REUSE_DIRTY) {
                return;                     // 复用:保留目录原样,直接按目录名复载
            }
            deleteCopyDir(map.id(), session.shortCode());
        }
        copyTree(templateDir, copyDir);
    }

    /**
     * 删除副本目录——<b>安全边界唯一的删除入口</b>。
     * 删前断言路径形态:目录必须直接位于副本根下、文件名恰为 {@code <mapId>-<短码>}
     * 且 mapId/短码不含路径分隔符(normalize 后不逃出副本根)——断言不过即抛,
     * 绝不递归删除任意路径。
     */
    private void deleteCopyDir(String mapId, String shortCode) throws IOException {
        String name = mapId + "-" + shortCode;
        Path root = copyRoot.toAbsolutePath().normalize();
        Path dir = copyRoot.resolve(name).toAbsolutePath().normalize();
        boolean shaped = !mapId.isEmpty() && !shortCode.isEmpty()
                && root.equals(dir.getParent())
                && dir.getFileName() != null && name.equals(dir.getFileName().toString())
                && !mapId.contains("/") && !mapId.contains("\\")
                && !shortCode.contains("/") && !shortCode.contains("\\");
        if (!shaped) {
            throw new IOException("副本目录路径形态断言失败,拒绝删除: " + dir
                    + "(期望副本根 " + copyRoot + " 下 <mapId>-<短码> 形态)");
        }
        deleteTree(dir);
    }

    /** 递归复制目录树(walk 前序:父目录先建;同名覆盖)。 */
    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path from : walk.toList()) {
                Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 递归删除目录树(子先父后)。调用方必须先过 {@link #deleteCopyDir} 的形态断言。 */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** 迟到/竞争回滚时的尽力卸载(失败只记日志,目录留给清残留兜底)。 */
    private void unloadQuietly(WorldHandle handle, String why) {
        try {
            if (!worldLoader.unloadWorld(handle.world(), false)) {
                log.log(Level.WARNING, "{0}{1}", new Object[] { why, handle.instanceName() });
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, why + handle.instanceName(), t);
        }
    }

    // ── 只读诊断面(★17 与状态不变式断言的观测口;零副作用) ─────────────

    /** 引用计数快照(副本目录名 → 计数)。 */
    public Map<String, Integer> refCounts() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (RefEntry entry : table.values()) {
            snapshot.put(entry.handle.instanceName(), entry.count.get());
        }
        return snapshot;
    }

    /** 引用计数表条目数(与 {@link #refCounts()} 的大小相等 = 条目 instanceName 无重复)。 */
    public int refEntryCount() {
        return table.size();
    }

    /** 进行中(未完成的获取)条目数——in-flight 必清断言的观测口。 */
    public int inflightCount() {
        return inFlight.size();
    }
}
