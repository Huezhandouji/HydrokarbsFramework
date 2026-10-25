package com.hydrokarbs.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 地图目录:全部已装载 {@link MapDefinition} 的查询面(施工计划 2.2;
 * 15-适配 §15.2 热重载纪律)。★v1.2(7.5;20-D1~D7):增第二输入通道——
 * 显式注册({@link #register}/{@link #unregister}),与文件装载
 * ({@link #replace})汇入<b>同一命名空间</b>,注册语义只有一份(20-D6)。
 *
 * <p><b>整体引用 volatile 原子替换</b>:内部持一个不可变快照,写操作(整表替换/
 * 注册/摘除)一次性换新——读方要么看到替换前的完整目录、要么看到替换后的完整目录,
 * 永远不会读到半截。持有旧快照中 {@link MapDefinition} 引用的人不受替换影响
 * (record 纯静态值,不存在可变状态被改写)。
 *
 * <p><b>双通道与来源</b>(20-D6):文件式(启动扫描 {@code maps/*.yml})与注册式
 * ({@code registerMap})共用同一命名空间;目录顺序 = 文件装载序 + 注册序(注册式
 * 追加在后,自动挑图取序首的策略因此可预期);{@code api.maps()} 对两种来源
 * 一视同仁,快照不区分来源(20-D6/Q3 未决倾向:诊断走本类注册表内部记录与日志)。
 *
 * <p><b>注册期校验(20-D3,响亮抛——错误当场可感,不留半注册态)</b>:
 * ① id 查重:与既有目录(文件式 + 注册式)撞名即抛,点名先到者与其来源
 * (文件/插件)——先到者保留;② tags:非 null 引用(MapDefinition 构造已强制,
 * 此处冗余显式校验;<b>空 tags 集合法</b>——等于不被任何模式按标签选中,20-D3);
 * ③ 世界模板目录存在性:{@code <模板根>/maps/templates/<mapId>/}(20-D2,模板根经
 * 构造注入 {@link Path},由装配传入——map 包是纯模型包,不 import bridge);
 * 缺失 = 响亮抛(14.39:onEnable 顺序 = 解包模板 → 构造 MapDefinition → registerMap)。
 *
 * <p><b>摘除(20-D4/20-D5)</b>:注册式摘除、文件式拒绝(声明所有权在文件装载);
 * 已不在目录 = 幂等 no-op;<b>「有会话正用 → 摘除被拒」的守卫不在这里</b>——
 * 本类是纯目录,会话查询归调用方({@code MiniGameApiImpl} 经
 * {@code SessionService.all()} 查存活会话当前实例;未释放世界句柄无公开查询口,
 * 按「当前实例正用」落最小守卫,7.5 施工决策 14)。<b>注册式不经 reload</b>:
 * {@link #replace} 整表替换只动文件式半边,注册式条目原样保留(20-D5;
 * 文件图与注册图撞名 → 文件图跳过 + WARN,先到者 = 注册式)。
 *
 * <p>线程契约:装载解析在虚拟线程做(§15.2),{@link #replace} 回到主线程执行;
 * 注册/摘除主线程(Bukkit 事件主线程派发,api javadoc 主线程调用口径)。
 * 本类只保证引用可见性(volatile),不做并发装配。
 */
public final class MapRegistry {

    private static final Logger LOG = Logger.getLogger(MapRegistry.class.getName());

    /** 注册条目:声明 + 提供方身份键(生产 = org.bukkit.plugin.Plugin;身份比较,7.2 要点 4 同款)。 */
    private record Registered(MapDefinition map, Object provider) {
    }

    /** 不可变快照(一次写操作的完整结果):id 索引 + 展示序 + 文件式半边 + 注册式半边。 */
    private record Snapshot(Map<String, MapDefinition> byId, List<MapDefinition> order,
                            List<MapDefinition> fileOrder, List<Registered> registered) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), List.of(), List.of(), List.of());

        /** 组装:文件式在前(装载序)、注册式在后(注册序);byId 同命名空间合并。 */
        static Snapshot of(List<MapDefinition> fileOrder, List<Registered> registered) {
            List<MapDefinition> order = new ArrayList<>(fileOrder);
            for (Registered entry : registered) {
                order.add(entry.map());
            }
            Map<String, MapDefinition> index = new LinkedHashMap<>();
            for (MapDefinition map : order) {
                index.put(map.id(), map);
            }
            return new Snapshot(Map.copyOf(index), List.copyOf(order), List.copyOf(fileOrder),
                    List.copyOf(registered));
        }
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** 世界模板根(注册期模板目录校验依据;null = 注册通道未装配,注册即响亮抛)。 */
    private final Path templatesRoot;

    /** 文件装载专用构造(既有调用零迁移;注册通道未装配——注册即响亮抛)。 */
    public MapRegistry() {
        this.templatesRoot = null;
    }

    /**
     * @param templatesRoot 世界模板根(数据目录 {@code maps/templates/},§15.2/20-D2;
     *                      装配传入,注册期校验 {@code <root>/<mapId>/} 目录存在性)
     */
    public MapRegistry(Path templatesRoot) {
        this.templatesRoot = Objects.requireNonNull(templatesRoot, "templatesRoot");
    }

    // ── 文件装载(既有通道;★v1.2:只动文件式半边,注册式不经 reload 触碰) ──

    /**
     * 文件式整表替换(旧快照持有者不受影响)。注册式条目原样保留(20-D5);
     * 文件图与注册图撞名 → 该文件图跳过 + WARN(先到者 = 注册式,同一命名空间)。
     */
    public void replace(Collection<MapDefinition> maps) {
        Objects.requireNonNull(maps, "maps");
        Snapshot current = snapshot;
        Set<String> registeredIds = new LinkedHashSet<>();
        for (Registered entry : current.registered()) {
            registeredIds.add(entry.map().id());
        }
        List<MapDefinition> fileOrder = new ArrayList<>();
        for (MapDefinition map : maps) {
            if (registeredIds.contains(map.id())) {
                LOG.log(Level.WARNING, "文件地图 {0} 与注册式地图撞名,装载跳过"
                        + "(注册式不经 reload 触碰,20-D5;先到者 = 注册式)", map.id());
                continue;
            }
            fileOrder.add(map);
        }
        snapshot = Snapshot.of(fileOrder, current.registered());
    }

    // ── 注册通道(★v1.2;MiniGameApi#registerMap 的委托目标) ────────────

    /**
     * 注册一张地图(注册期校验见类注;重复 id 响亮抛点名先到者)。主线程调用。
     *
     * @param provider 提供方身份键(生产 = org.bukkit.plugin.Plugin;身份比较,
     *                 按提供方兜底摘除与诊断点名用)
     * @param map      建好的地图声明(纯静态值,20-D1「交成品不交工厂」)
     * @return 注册的同一声明实例(目录中的正式条目)
     * @throws IllegalStateException    模板根未装配 / id 与既有目录撞名(点名先到者)
     * @throws IllegalArgumentException id 空白 / 世界模板目录不存在(带路径)
     */
    public MapDefinition register(Object provider, MapDefinition map) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(map.tags(), "tags");   // 非 null 引用(MapDefinition 已强制;空集合法,20-D3)
        if (map.id().isBlank()) {
            throw new IllegalArgumentException("地图 id 不可为空白串(注册式与文件式同一命名空间)");
        }
        if (templatesRoot == null) {
            throw new IllegalStateException(
                    "地图注册通道的模板根未配置(MapRegistry 需经构造注入模板根;装配缺失)");
        }
        Path templateDir = templatesRoot.resolve(map.id());
        if (!Files.isDirectory(templateDir)) {
            throw new IllegalArgumentException("地图 " + map.id() + " 的世界模板目录不存在: " + templateDir
                    + "(14.39:注册前必须先解包模板——onEnable 顺序 = 解包 → 构造 MapDefinition → registerMap)");
        }
        Snapshot current = snapshot;
        if (current.byId().containsKey(map.id())) {
            throw new IllegalStateException("重复的地图 id: " + map.id() + "(先到者: "
                    + describeSource(current, map.id()) + ";后来者来源: 插件注册)"
                    + "——注册式与文件式同一命名空间,先到者保留(20-D3)");
        }
        List<Registered> registered = new ArrayList<>(current.registered());
        registered.add(new Registered(map, provider));
        snapshot = Snapshot.of(current.fileOrder(), List.copyOf(registered));
        return map;
    }

    /**
     * 摘除一张注册式地图(20-D4):文件式 → 响亮拒绝(声明所有权在文件装载);
     * 已不在目录 → 幂等 no-op 返回 false。「有会话正用 → 摘除被拒」守卫在调用方
     * (本类是纯目录,见类注)。主线程调用。
     *
     * @return true = 已摘除;false = 目录中无此 id(幂等 no-op)
     * @throws IllegalStateException 该 id 是文件式地图
     */
    public boolean unregister(Object provider, String mapId) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(mapId, "mapId");
        Snapshot current = snapshot;
        if (!current.byId().containsKey(mapId)) {
            return false;                               // 幂等:已不在目录 = no-op(07 §7.1 javadoc 口径)
        }
        boolean registeredHere = current.registered().stream().anyMatch(r -> r.map().id().equals(mapId));
        if (!registeredHere) {
            throw new IllegalStateException("地图 " + mapId + " 是文件式地图(id 来自 maps/*.yml),"
                    + "不得经 unregisterMap 摘除(20-D4/D5;要换文件图 = 改 yml 后 reload 装载)");
        }
        List<Registered> remaining = current.registered().stream()
                .filter(entry -> !entry.map().id().equals(mapId))
                .toList();
        snapshot = Snapshot.of(current.fileOrder(), remaining);
        return true;
    }

    /** 指定提供方注册的全部地图 id(按提供方兜底摘除的查询口;身份比较,7.2 要点 4)。 */
    public Set<String> registeredIdsOf(Object provider) {
        Objects.requireNonNull(provider, "provider");
        Set<String> ids = new LinkedHashSet<>();
        for (Registered entry : snapshot.registered()) {
            if (entry.provider() == provider) {
                ids.add(entry.map().id());
            }
        }
        return Set.copyOf(ids);
    }

    /** 先到者来源描述(重复 id 响亮点名;注册式带提供方类名,20-D3)。 */
    private static String describeSource(Snapshot current, String mapId) {
        for (Registered entry : current.registered()) {
            if (entry.map().id().equals(mapId)) {
                return "插件注册(" + entry.provider() + ")";
            }
        }
        return "文件装载(maps/*.yml)";
    }

    // ── 查询面(既有,零改动) ────────────────────────────────────

    /** 按 id 查地图;无此 id 返回 empty。 */
    public Optional<MapDefinition> byId(String mapId) {
        return Optional.ofNullable(snapshot.byId.get(mapId));
    }

    /** 按 tag 过滤(装载序 + 注册序);无匹配返回空列表。 */
    public List<MapDefinition> byTag(String tag) {
        List<MapDefinition> matched = new ArrayList<>();
        for (MapDefinition map : snapshot.order) {
            if (map.tags().contains(tag)) {
                matched.add(map);
            }
        }
        return List.copyOf(matched);
    }

    /** 全部地图(文件装载序 + 注册序,不可变)。 */
    public List<MapDefinition> all() {
        return snapshot.order;
    }
}
