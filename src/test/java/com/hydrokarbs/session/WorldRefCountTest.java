package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hydrokarbs.bridge.world.WorldLoader;
import com.hydrokarbs.bridge.world.WorldPortImpl;
import com.hydrokarbs.engine.port.ResetStrategy;
import com.hydrokarbs.engine.port.WorldHandle;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.map.MapDefinition;
import com.hydrokarbs.map.RegionSet;
import com.hydrokarbs.testkit.FakeWorldPort;

/**
 * ★17 世界零泄漏(16-路线图与验收 §16.2;03-会话与模式切换 §3.7;施工计划 2.4)——
 * <b>首建后永久生效,任何后续阶段不得为通过功能放宽本类断言</b>。
 *
 * <p>检验对象是 {@link WorldPortImpl} 的计数与处置逻辑:注入假 {@link WorldLoader}
 * (内存假世界、记录装卸调用与执行器域)驱动,百次循环离线可跑;真服的世界装卸抽查
 * 并入 2.5 闭环,两处合计才是 ★17 的完整证据。
 *
 * <p>覆盖七组断言:①100 次循环零泄漏;②并发合并与计数;③两种处置策略;
 * ④缺模板;⑤准备超时;⑥REUSE_DIRTY 同会话复载不复制;⑦清残留的路径形态边界;
 * 另附状态不变式四条(count≥1 / 同 instanceName 至多一条 / in-flight 必清 /
 * 归零必移除)、线程契约断言(装卸在主线程执行器、复制删目录在 IO 执行器)与
 * release 两种完成时机语义。
 */
class WorldRefCountTest {

    // ── 执行器域记录(线程契约断言:装卸=main 域,复制/删除=io 域) ──────────

    /** 当前执行器域("main"/"io"/"test" 外);直跑执行器包装时置位。 */
    private static final AtomicReference<String> ZONE = new AtomicReference<>("test");

    private static final class FakeWorldLoader implements WorldLoader {

        final List<String> zonesAtCreate = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> zonesAtUnload = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger createCount = new AtomicInteger();
        final AtomicInteger unloadCount = new AtomicInteger();
        final ConcurrentMap<World, String> worldNames = new ConcurrentHashMap<>();
        final Set<String> loaded = ConcurrentHashMap.newKeySet();
        volatile boolean unloadResult = true;
        volatile boolean failNextCreate;

        @Override
        public World createWorld(String instanceName, Path copiedDir) {
            zonesAtCreate.add(ZONE.get());
            createCount.incrementAndGet();
            if (failNextCreate) {
                failNextCreate = false;
                throw new IllegalStateException("注入的加载失败: " + instanceName);
            }
            World world = FakeWorldPort.proxyWorld();
            worldNames.put(world, instanceName);
            loaded.add(instanceName);
            return world;
        }

        @Override
        public boolean unloadWorld(World world, boolean save) {
            zonesAtUnload.add(ZONE.get());
            unloadCount.incrementAndGet();
            String name = worldNames.remove(world);
            if (name != null) {
                loaded.remove(name);
            }
            return unloadResult;
        }

        @Override
        public List<String> loadedWorldNames() {
            return List.copyOf(loaded);
        }
    }

    /** 挂起式 IO 执行器(并发合并/超时测试):每任务一线程,先等门闩放行。 */
    private static final class GatedThreadedIo implements java.util.concurrent.Executor {

        private final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger tasks = new AtomicInteger();

        @Override
        public void execute(java.lang.Runnable command) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ZONE.set("io");
                try {
                    command.run();
                } finally {
                    ZONE.set("test");
                }
                tasks.incrementAndGet();
            }, "world-io-test");
            worker.start();
        }

        void open() {
            gate.countDown();
        }
    }

    /** 测试装置:模板根/副本根 + 假载器 + 直跑(或门闩)双执行器 + 真端口实现。 */
    private static final class Rig {

        final Path templates;
        final Path copies;
        final FakeWorldLoader loader = new FakeWorldLoader();
        final AtomicInteger ioTasks = new AtomicInteger();
        final GatedThreadedIo gatedIo;      // null = 直跑 IO
        final WorldPortImpl port;

        Rig(Path templates, Path copies, GatedThreadedIo gatedIo, long timeoutMillis) {
            this.templates = templates;
            this.copies = copies;
            this.gatedIo = gatedIo;
            java.util.concurrent.Executor io = gatedIo != null ? gatedIo : command -> {
                ZONE.set("io");
                try {
                    command.run();
                } finally {
                    ZONE.set("test");
                }
                ioTasks.incrementAndGet();
            };
            java.util.concurrent.Executor main = command -> {
                ZONE.set("main");
                try {
                    command.run();
                } finally {
                    ZONE.set("test");
                }
            };
            Logger logger = Logger.getLogger("world-ref-count-test");
            logger.setUseParentHandlers(false);
            port = new WorldPortImpl(loader, templates, copies, main, io,
                    java.time.Duration.ofMillis(timeoutMillis), logger);
        }

        static Rig direct(Path templates, Path copies) {
            return new Rig(templates, copies, null, 30_000L);
        }
    }

    // ── 便捷件 ───────────────────────────────────────────────

    private static final SessionId SESSION = new SessionId("arena-1");

    private static MapDefinition map(String id, ResetStrategy reset) {
        return new MapDefinition(id, id, "w-" + id, Set.of("common"), null, java.util.Map.of(),
                RegionSet.empty(), List.of(), java.util.Map.of(), reset);
    }

    private static void layTemplate(Path templates, String mapId, String... files) throws IOException {
        Path dir = templates.resolve(mapId);
        Files.createDirectories(dir);
        for (String file : files) {
            Files.writeString(dir.resolve(file), "template-content:" + file);
        }
    }

    private static WorldHandle await(CompletableFuture<WorldHandle> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待句柄被中断", e);
        } catch (ExecutionException e) {
            throw new AssertionError("获取句柄异常完成: " + e.getCause(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("等待句柄超时(5s)", e);
        }
    }

    private static void awaitVoid(CompletableFuture<Void> future) {
        try {
            assertNull(future.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待释放被中断", e);
        } catch (ExecutionException e) {
            throw new AssertionError("释放异常完成: " + e.getCause(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("等待释放超时(5s)", e);
        }
    }

    private static void assertInvariants(WorldPortImpl port, String where) {
        // 不变式①:表中每个句柄 count ≥ 1;②:同一 instanceName 至多一条
        // (条目数 == 唯一 instanceName 数;refCounts 的 Map 键去重会掩盖重复,须双口对账);
        // ③:in-flight 必清(④归零必移除并入各用例的显式断言)
        var counts = port.refCounts();
        counts.forEach((name, count) -> assertTrue(count >= 1,
                where + ": count ≥ 1 被破(" + name + "=" + count + ")"));
        assertEquals(port.refEntryCount(), counts.size(),
                where + ": 条目数与唯一 instanceName 数应相等(同 instanceName 至多一条)");
        assertEquals(0, port.inflightCount(), where + ": in-flight 应为空");
    }

    // ── ① 100 次循环零泄漏(+不变式/线程契约并入) ─────────────────────

    @Test
    void hundredCycleAcquireReleaseLeavesZeroLeak(@TempDir Path templates, @TempDir Path copies)
            throws IOException {
        layTemplate(templates, "lobby_main", "level.dat", "region.marker");
        layTemplate(templates, "lobby_mini", "level.dat");
        Rig rig = Rig.direct(templates, copies);
        MapDefinition reuseMap = map("lobby_main", ResetStrategy.REUSE_DIRTY);
        MapDefinition restoreMap = map("lobby_mini", ResetStrategy.TEMPLATE_RESTORE);

        // 模拟切换时序:先获取新图,后释放旧图(§3.7 释放时机);两图交替 100 轮,
        // 两种处置策略都随轮次被覆盖(REUSE_DIRTY 复载/TEMPLATE_RESTORE 重拷)
        WorldHandle current = await(rig.port.acquire(reuseMap, SESSION));
        int reuseReloads = 0;
        for (int i = 1; i <= 100; i++) {
            boolean toMini = i % 2 == 1;
            WorldHandle next = await(rig.port.acquire(toMini ? restoreMap : reuseMap, SESSION));
            WorldHandle retiring = current;
            awaitVoid(rig.port.release(retiring));
            // 回图重新获取是全新句柄(归零条目已移除,不依赖缓存侥幸)
            if (retiring.map().id().equals(next.map().id())) {
                assertNotSame(retiring, next, "同 key 归零后的重新获取应发放新句柄");
            }
            current = next;
            if (!toMini) {
                reuseReloads++;
            }
            assertInvariants(rig.port, "第 " + i + " 轮后");
        }
        awaitVoid(rig.port.release(current));
        assertTrue(reuseReloads >= 49, "100 轮中 REUSE_DIRTY 侧应被反复覆盖(实际 " + reuseReloads + " 轮)");

        // 零泄漏四断言:表清空、无未释放句柄(装卸次数相等)、无残留已加载副本名、
        // 目录按策略处置(REUSE_DIRTY 保留 / TEMPLATE_RESTORE 消失)
        assertTrue(rig.port.refCounts().isEmpty(), "100 次循环后引用计数表应清空: " + rig.port.refCounts());
        assertEquals(0, rig.port.inflightCount(), "in-flight 应已清空");
        assertEquals(rig.loader.createCount.get(), rig.loader.unloadCount.get(),
                "每次加载都应配对一次卸载(create=" + rig.loader.createCount
                        + ", unload=" + rig.loader.unloadCount + ")");
        assertTrue(rig.loader.loadedWorldNames().isEmpty(),
                "已加载世界名单应无残留副本: " + rig.loader.loadedWorldNames());
        assertTrue(Files.isDirectory(copies.resolve("lobby_main-" + SESSION.shortCode())),
                "REUSE_DIRTY 释放后应保留副本目录(同会话复载用)");
        assertFalse(Files.exists(copies.resolve("lobby_mini-" + SESSION.shortCode())),
                "TEMPLATE_RESTORE 释放后应删除副本目录");
        assertInvariants(rig.port, "循环结束后");
        // 线程契约:装卸全部发生在注入的主线程执行器域内;复制/删除任务跑在 IO 执行器上
        assertTrue(rig.loader.zonesAtCreate.stream().allMatch("main"::equals),
                "createWorld 应全部在主线程执行器域: " + rig.loader.zonesAtCreate);
        assertTrue(rig.loader.zonesAtUnload.stream().allMatch("main"::equals),
                "unloadWorld 应全部在主线程执行器域: " + rig.loader.zonesAtUnload);
        assertTrue(rig.ioTasks.get() >= 101, "复制/删除任务应全部经由 IO 执行器(实际 "
                + rig.ioTasks.get() + ")");
    }

    // ── ② 并发合并与计数(5 次并发同句柄、计数 5、5 次释放才一次卸载) ────────

    @Test
    @SuppressWarnings("unchecked")
    void concurrentAcquireMergesToSingleCreateAndCountsFive(
            @TempDir Path templates, @TempDir Path copies) throws Exception {
        layTemplate(templates, "arena_x", "level.dat");
        GatedThreadedIo gatedIo = new GatedThreadedIo();
        Rig rig = new Rig(templates, copies, gatedIo, 30_000L);
        MapDefinition target = map("arena_x", ResetStrategy.REUSE_DIRTY);

        // 门闩关住 IO:首个获取在途(复制未完成),后续 4 个并发 miss 合并到同一 future
        CompletableFuture<WorldHandle> first = rig.port.acquire(target, SESSION);
        CompletableFuture<WorldHandle>[] merged = new CompletableFuture[4];
        for (int i = 0; i < merged.length; i++) {
            merged[i] = rig.port.acquire(target, SESSION);
        }
        assertEquals(1, rig.port.inflightCount(), "并发期应恰有一个 in-flight");
        gatedIo.open();

        WorldHandle handle = await(first);
        for (CompletableFuture<WorldHandle> future : merged) {
            assertSame(handle, await(future), "并发合并:全部等待者应拿到同一句柄");
        }
        assertEquals(1, rig.loader.createCount.get(), "同图并发只复制一次(只加载一次)");
        assertEquals(5, rig.port.refCounts().get(handle.instanceName()).intValue(),
                "5 次并发获取应计数 5: " + rig.port.refCounts());
        assertInvariants(rig.port, "并发获取完成后");

        // release 5 次后才触发一次卸载;前 4 次未归零立即完成
        for (int i = 1; i <= 4; i++) {
            CompletableFuture<Void> partial = rig.port.release(handle);
            assertTrue(partial.isDone(), "未归零的释放应立即完成(第 " + i + " 次)");
            assertEquals(0, rig.loader.unloadCount.get(), "未归零不应触发卸载");
        }
        awaitVoid(rig.port.release(handle));
        assertEquals(1, rig.loader.unloadCount.get(), "第 5 次释放才触发唯一一次卸载");
        assertTrue(rig.port.refCounts().isEmpty(), "归零条目必被移除: " + rig.port.refCounts());
        assertInvariants(rig.port, "全部释放后");
    }

    // ── ③ 两种处置策略 + ⑥ REUSE_DIRTY 同会话复载不复制(脏标记黑盒) ─────────

    @Test
    void resetStrategiesDisposeCopiesAccordingly(@TempDir Path templates, @TempDir Path copies)
            throws IOException {
        layTemplate(templates, "keep_x", "level.dat");
        layTemplate(templates, "wipe_y", "level.dat");
        Rig rig = Rig.direct(templates, copies);
        MapDefinition keep = map("keep_x", ResetStrategy.REUSE_DIRTY);
        MapDefinition wipe = map("wipe_y", ResetStrategy.TEMPLATE_RESTORE);

        // REUSE_DIRTY:归零保留目录;同会话再次获取直接复载、不复制
        // (黑盒脏标记:复制会清残留重抄使标记消失;复用则标记原样保留)
        WorldHandle kept = await(rig.port.acquire(keep, SESSION));
        Path keptDir = copies.resolve(kept.instanceName());
        Path keptMarker = keptDir.resolve("dirty-marker.txt");
        Files.writeString(keptMarker, "局内留下的痕迹");
        awaitVoid(rig.port.release(kept));
        assertTrue(Files.isDirectory(keptDir), "REUSE_DIRTY 归零应保留副本目录");
        WorldHandle reloaded = await(rig.port.acquire(keep, SESSION));
        assertTrue(Files.exists(keptMarker), "同会话复载不应复制(脏标记应原样保留)");
        assertNotSame(kept, reloaded, "复载发放新句柄(旧条目已移除)");
        assertEquals(2, rig.loader.createCount.get(), "复载仍应重新加载世界");
        awaitVoid(rig.port.release(reloaded));

        // TEMPLATE_RESTORE:归零删除目录;再次获取从模板重新复制(标记消失)
        WorldHandle wiped = await(rig.port.acquire(wipe, SESSION));
        Path wipedDir = copies.resolve(wiped.instanceName());
        Files.writeString(wipedDir.resolve("dirty-marker.txt"), "应被模板还原抹掉");
        awaitVoid(rig.port.release(wiped));
        assertFalse(Files.exists(wipedDir), "TEMPLATE_RESTORE 归零应删除副本目录");
        WorldHandle restored = await(rig.port.acquire(wipe, SESSION));
        assertFalse(Files.exists(wipedDir.resolve("dirty-marker.txt")),
                "重拷后不应残留脏标记");
        assertTrue(Files.exists(wipedDir.resolve("level.dat")), "重拷应以模板内容为准");
        awaitVoid(rig.port.release(restored));
        assertInvariants(rig.port, "两策略处置后");
    }

    // ── ④ 缺模板:future 异常完成且计数未入表 ───────────────────────

    @Test
    void missingTemplateFailsCleanlyWithoutTableEntry(@TempDir Path templates, @TempDir Path copies)
            throws IOException {
        Files.createDirectories(templates);                 // 模板根存在但无该地图
        Rig rig = Rig.direct(templates, copies);
        CompletableFuture<WorldHandle> future =
                rig.port.acquire(map("ghost", ResetStrategy.REUSE_DIRTY), SESSION);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("模板目录缺失"),
                "缺模板应点名模板路径: " + failure.getCause().getMessage());
        assertTrue(rig.port.refCounts().isEmpty(), "失败获取不得入表");
        assertEquals(0, rig.port.inflightCount(), "失败后 in-flight 必清");
        assertEquals(0, rig.loader.createCount.get(), "缺模板不应触发加载");

        // 同名穿越型地图 id:模板解析不成立 → 同一失败路径,且副本根零新增
        // (walk 含根目录自身,须剔除后计数)
        CompletableFuture<WorldHandle> traversal =
                rig.port.acquire(map("../evil", ResetStrategy.TEMPLATE_RESTORE), SESSION);
        assertThrows(ExecutionException.class, () -> traversal.get(5, TimeUnit.SECONDS));
        try (Stream<Path> walk = Files.walk(copies)) {
            assertEquals(0, walk.filter(path -> !path.equals(copies)).count(),
                    "穿越型地图 id 不得在副本根产生任何路径");
        }
    }

    // ── ⑤ 准备超时(注入短超时):异常完成、in-flight 已清、后续可重走 ─────────

    @Test
    void prepareTimeoutCompletesExceptionallyAndAllowsRetry(
            @TempDir Path templates, @TempDir Path copies) throws Exception {
        layTemplate(templates, "slow_z", "level.dat");
        GatedThreadedIo gatedIo = new GatedThreadedIo();
        Rig rig = new Rig(templates, copies, gatedIo, 150L);       // 短超时替代 30s(可测口径)
        MapDefinition target = map("slow_z", ResetStrategy.REUSE_DIRTY);

        // IO 门闩关住 → 复制挂起 → 150ms 超时:future 异常完成、in-flight 清、未入表
        CompletableFuture<WorldHandle> hung = rig.port.acquire(target, SESSION);
        assertFalse(hung.isDone(), "门闩未开时获取应挂起");
        assertThrows(ExecutionException.class, () -> hung.get(2, TimeUnit.SECONDS));
        assertTrue(hung.isCompletedExceptionally(), "超时应异常完成");
        assertEquals(0, rig.port.inflightCount(), "超时后 in-flight 必清(否则该 key 永远等死 future)");
        assertTrue(rig.port.refCounts().isEmpty(), "超时获取不得入表");

        // 放行门闩:迟到的复制流水走到加载点被迟到守卫拦下(不加载、不入表)
        gatedIo.open();
        awaitQuiet(200);
        assertEquals(0, rig.loader.createCount.get(),
                "超时已拒绝的获取,迟到流水不得加载世界入表");

        // 后续同 key 获取可正常走通(不依赖已死的 in-flight)
        WorldHandle retried = await(rig.port.acquire(target, SESSION));
        assertEquals(1, rig.loader.createCount.get(), "重试获取应正常加载");
        awaitVoid(rig.port.release(retried));
        assertInvariants(rig.port, "超时与重试后");
    }

    /** 安静等待(门闩放行后的迟到流水收尾)。 */
    private static void awaitQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── ⑦ 清残留与删除的路径形态边界(仅限副本根下本实现形态) ─────────────

    @Test
    void copyCleanupIsConfinedToManagedShape(@TempDir Path templates, @TempDir Path copies)
            throws IOException {
        layTemplate(templates, "shaped_a", "level.dat", "wanted.txt");
        Rig rig = Rig.direct(templates, copies);
        MapDefinition shaped = map("shaped_a", ResetStrategy.TEMPLATE_RESTORE);
        SessionId session = SESSION;
        String instanceName = "shaped_a-" + session.shortCode();

        // 预置三类干扰物:同名残留(带垃圾)、同根异名目录、模板根文件
        Path staleDir = copies.resolve(instanceName);
        Files.createDirectories(staleDir);
        Files.writeString(staleDir.resolve("junk-leftover.txt"), "上次异常残留");
        Path unrelatedDir = copies.resolve("zzz-unrelated-1");
        Files.createDirectories(unrelatedDir);
        Files.writeString(unrelatedDir.resolve("keep.txt"), "与本实现无关的目录");
        Path templateMarker = templates.resolve("shaped_a").resolve("level.dat");

        // TEMPLATE_RESTORE 获取:同名残留被清(形态匹配)后以模板内容重建;
        // 异名目录与模板根原封不动——删除范围仅限副本根下本实现形态
        WorldHandle handle = await(rig.port.acquire(shaped, session));
        assertFalse(Files.exists(staleDir.resolve("junk-leftover.txt")), "同名残留应被清空");
        assertTrue(Files.exists(staleDir.resolve("level.dat")), "副本应以模板内容重建");
        assertTrue(Files.exists(unrelatedDir.resolve("keep.txt")), "异名目录不得被触碰");
        assertTrue(Files.exists(templateMarker), "模板母本不得被触碰");

        // 归零删除也只删本实现形态的目录,异名目录仍在
        awaitVoid(rig.port.release(handle));
        assertFalse(Files.exists(staleDir), "TEMPLATE_RESTORE 归零删除副本目录");
        assertTrue(Files.exists(unrelatedDir), "归零删除后异名目录仍应在");
        assertTrue(Files.exists(templateMarker), "归零删除不得波及模板母本");
    }

    // ── release 两种完成时机语义 + 同图重开计数配平 ────────────────────

    @Test
    void releaseCompletionSemanticsAndSameMapBalance(@TempDir Path templates, @TempDir Path copies)
            throws IOException {
        layTemplate(templates, "paired_p", "level.dat");
        Rig rig = Rig.direct(templates, copies);
        MapDefinition target = map("paired_p", ResetStrategy.REUSE_DIRTY);

        // 未归零:立即完成,零装卸、零 IO 任务
        WorldHandle first = await(rig.port.acquire(target, SESSION));
        WorldHandle second = await(rig.port.acquire(target, SESSION));   // 同图重开:命中 +1
        assertSame(first, second, "同 (map, session) 重复获取应幂等返回同句柄");
        int ioTasksBefore = rig.ioTasks.get();
        CompletableFuture<Void> partial = rig.port.release(first);
        assertTrue(partial.isDone(), "未归零释放应立即完成");
        assertEquals(0, rig.loader.unloadCount.get(), "未归零不应卸载");
        assertEquals(ioTasksBefore, rig.ioTasks.get(), "未归零不应派发 IO 任务");

        // 归零(REUSE_DIRTY):卸载完成后 future 才完成,目录保留
        CompletableFuture<Void> finalRelease = rig.port.release(second);
        assertTrue(finalRelease.isDone(), "归零处置(REUSE_DIRTY)应同步完成");
        assertEquals(1, rig.loader.unloadCount.get(), "归零应恰卸载一次");
        assertTrue(Files.isDirectory(copies.resolve(first.instanceName())),
                "REUSE_DIRTY 归零保留目录");

        // 重复释放:幂等无害(条目已移除 → 未知句柄直接完成)
        assertDoesNotThrow(() -> awaitVoid(rig.port.release(first)));
        assertDoesNotThrow(() -> awaitVoid(rig.port.release(second)));
        assertEquals(1, rig.loader.unloadCount.get(), "重复释放不得再次卸载");
        assertInvariants(rig.port, "配平与幂等检查后");

        // 归零(TEMPLATE_RESTORE)的 future 完成点 = 目录删除完成之后
        layTemplate(templates, "paired_q", "level.dat");
        MapDefinition wipe = map("paired_q", ResetStrategy.TEMPLATE_RESTORE);
        WorldHandle wiped = await(rig.port.acquire(wipe, SESSION));
        int ioBeforeWipe = rig.ioTasks.get();
        CompletableFuture<Void> wipeRelease = rig.port.release(wiped);
        assertTrue(wipeRelease.isDone(), "归零处置(TEMPLATE_RESTORE)应在删除完成后完成");
        assertFalse(Files.exists(copies.resolve(wiped.instanceName())), "删除已生效");
        assertTrue(rig.ioTasks.get() > ioBeforeWipe, "删目录应派发到 IO 执行器");
    }

    // ── 卸载失败路径:SEVERE 语义 + 保留目录 + 不阻塞(处置完成) ──────────

    @Test
    void unloadFailureKeepsDirAndStillCompletes(@TempDir Path templates, @TempDir Path copies)
            throws IOException {
        layTemplate(templates, "stubborn_s", "level.dat");
        Rig rig = Rig.direct(templates, copies);
        List<String> logLines = new java.util.concurrent.CopyOnWriteArrayList<>();
        Logger spy = Logger.getLogger("world-ref-count-unload-fail");
        spy.setUseParentHandlers(false);
        spy.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                logLines.add(record.getLevel() + "|" + record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        // 以注入失败日志与失败卸载的端口复测
        WorldPortImpl failingPort = new WorldPortImpl(rig.loader, templates, copies,
                command -> {
                    ZONE.set("main");
                    try {
                        command.run();
                    } finally {
                        ZONE.set("test");
                    }
                },
                command -> {
                    ZONE.set("io");
                    try {
                        command.run();
                    } finally {
                        ZONE.set("test");
                    }
                }, java.time.Duration.ofSeconds(30), spy);
        rig.loader.unloadResult = false;       // 注入:Bukkit.unloadWorld 返回 false

        MapDefinition target = map("stubborn_s", ResetStrategy.TEMPLATE_RESTORE);
        WorldHandle handle = await(failingPort.acquire(target, SESSION));
        Path dir = copies.resolve(handle.instanceName());
        awaitVoid(failingPort.release(handle));       // 卸载失败不阻塞:处置仍完成

        assertEquals(0, failingPort.refCounts().size(), "归零条目仍应移除");
        assertTrue(Files.isDirectory(dir), "卸载失败应保留副本目录");
        assertTrue(logLines.stream().anyMatch(line -> line.startsWith("SEVERE|")
                        && line.contains("保留目录")),
                "卸载失败应记 SEVERE 并注明保留目录: " + logLines);
    }
}
