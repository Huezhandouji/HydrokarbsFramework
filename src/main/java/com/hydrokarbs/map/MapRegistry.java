package com.hydrokarbs.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 地图目录:全部已注册 {@link MapDefinition} 的查询面(施工计划 2.2;
 * 15-适配 §15.2 热重载纪律)。★单注册通道收敛(2026-10-27,经用户裁决):
 * 文件式装载通道({@code maps/*.yml} 文件式整表替换)已删除——地图只能由外部
 * 调用方经 {@link #register} 注册,声明所有权在注册的提供方(20-D1「交成品
 * 不交工厂」),注册语义只有一份(20-D6)。
 *
 * <p><b>整体引用 volatile 原子替换</b>:内部持一个不可变快照,写操作(注册/摘除)
 * 一次性换新——读方要么看到替换前的完整目录、要么看到替换后的完整目录,
 * 永远不会读到半截。持有旧快照中 {@link MapDefinition} 引用的人不受替换影响
 * (record 纯静态值,不存在可变状态被改写)。
 *
 * <p><b>目录顺序 = 注册序</b>:先注册者在前(自动挑图取序首的策略因此可预期);
 * {@code api.maps()} 的顺序即此序。
 *
 * <p><b>注册期校验(20-D3,响亮抛——错误当场可感,不留半注册态)</b>:
 * ① id 查重:与既有目录撞名即抛,点名先到者及其提供方——先到者保留;
 * ② tags:非 null 引用(MapDefinition 构造已强制,此处冗余显式校验;<b>空 tags
 * 集合法</b>——等于不被任何模式按标签选中,20-D3);③ 世界模板目录存在性:
 * {@code <模板根>/<mapId>/}(20-D2,模板根 = 数据目录 {@code maps/templates/},
 * 经构造注入 {@link Path},由装配传入——map 包是纯模型包,不 import bridge);
 * 缺失 = 响亮抛(14.39:onEnable 顺序 = 解包模板 → 构造 MapDefinition → registerMap)。
 *
 * <p><b>摘除(20-D4)</b>:已不在目录 = 幂等 no-op;<b>「有会话正用 → 摘除被拒」
 * 的守卫不在这里</b>——本类是纯目录,会话查询归调用方({@code MiniGameApiImpl} 经
 * {@code SessionService.all()} 查存活会话当前实例;未释放世界句柄无公开查询口,
 * 按「当前实例正用」落最小守卫,7.5 施工决策 14)。
 *
 * <p>线程契约:注册/摘除主线程(Bukkit 事件主线程派发,api javadoc 主线程调用口径)。
 * 本类只保证引用可见性(volatile),不做并发装配。
 */
public final class MapRegistry {

    /** 注册条目:声明 + 提供方身份键(生产 = org.bukkit.plugin.Plugin;身份比较,7.2 要点 4 同款)。 */
    private record Registered(MapDefinition map, Object provider) {
    }

    /** 不可变快照(一次写操作的完整结果):id 索引 + 展示序(= 注册序)+ 注册条目。 */
    private record Snapshot(Map<String, MapDefinition> byId, List<MapDefinition> order,
                            List<Registered> registered) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), List.of(), List.of());

        /** 组装:order = 注册序,byId 与之同源。 */
        static Snapshot of(List<Registered> registered) {
            List<MapDefinition> order = new ArrayList<>();
            for (Registered entry : registered) {
                order.add(entry.map());
            }
            Map<String, MapDefinition> index = new LinkedHashMap<>();
            for (MapDefinition map : order) {
                index.put(map.id(), map);
            }
            return new Snapshot(Map.copyOf(index), List.copyOf(order), List.copyOf(registered));
        }
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** 世界模板根(注册期模板目录校验依据;构造必填)。 */
    private final Path templatesRoot;

    /**
     * @param templatesRoot 世界模板根(数据目录 {@code maps/templates/},§15.2/20-D2;
     *                      装配传入,注册期校验 {@code <root>/<mapId>/} 目录存在性)
     */
    public MapRegistry(Path templatesRoot) {
        this.templatesRoot = Objects.requireNonNull(templatesRoot, "templatesRoot");
    }

    // ── 注册通道(MiniGameApi#registerMap 的委托目标) ────────────────────

    /**
     * 注册一张地图(注册期校验见类注;重复 id 响亮抛点名先到者)。主线程调用。
     *
     * @param provider 提供方身份键(生产 = org.bukkit.plugin.Plugin;身份比较,
     *                 按提供方兜底摘除与诊断点名用)
     * @param map      建好的地图声明(纯静态值,20-D1「交成品不交工厂」)
     * @return 注册的同一声明实例(目录中的正式条目)
     * @throws IllegalStateException    id 与既有目录撞名(点名先到者)
     * @throws IllegalArgumentException id 空白 / 世界模板目录不存在(带路径)
     */
    public MapDefinition register(Object provider, MapDefinition map) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(map.tags(), "tags");   // 非 null 引用(MapDefinition 已强制;空集合法,20-D3)
        if (map.id().isBlank()) {
            throw new IllegalArgumentException("地图 id 不可为空白串(注册声明必填)");
        }
        Path templateDir = templatesRoot.resolve(map.id());
        if (!Files.isDirectory(templateDir)) {
            throw new IllegalArgumentException("地图 " + map.id() + " 的世界模板目录不存在: " + templateDir
                    + "(14.39:注册前必须先解包模板——onEnable 顺序 = 解包 → 构造 MapDefinition → registerMap)");
        }
        Snapshot current = snapshot;
        if (current.byId().containsKey(map.id())) {
            throw new IllegalStateException("重复的地图 id: " + map.id() + "(先到者: "
                    + describeSource(current, map.id()) + ";后来者: 本次注册)"
                    + "——同一注册通道,先到者保留(20-D3)");
        }
        List<Registered> registered = new ArrayList<>(current.registered());
        registered.add(new Registered(map, provider));
        snapshot = Snapshot.of(List.copyOf(registered));
        return map;
    }

    /**
     * 摘除一张地图(20-D4):已不在目录 → 幂等 no-op 返回 false。「有会话正用 →
     * 摘除被拒」守卫在调用方(本类是纯目录,见类注)。主线程调用。
     *
     * @return true = 已摘除;false = 目录中无此 id(幂等 no-op)
     */
    public boolean unregister(Object provider, String mapId) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(mapId, "mapId");
        Snapshot current = snapshot;
        if (!current.byId().containsKey(mapId)) {
            return false;                               // 幂等:已不在目录 = no-op(07 §7.1 javadoc 口径)
        }
        List<Registered> remaining = current.registered().stream()
                .filter(entry -> !entry.map().id().equals(mapId))
                .toList();
        snapshot = Snapshot.of(remaining);
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

    /** 先到者来源描述(重复 id 响亮点名;带提供方类名,20-D3)。 */
    private static String describeSource(Snapshot current, String mapId) {
        for (Registered entry : current.registered()) {
            if (entry.map().id().equals(mapId)) {
                return "插件注册(" + entry.provider() + ")";
            }
        }
        throw new IllegalStateException("目录内部不一致: id " + mapId + " 在 id 索引中但无注册条目");
    }

    // ── 查询面(既有,零改动) ────────────────────────────────────

    /** 按 id 查地图;无此 id 返回 empty。 */
    public Optional<MapDefinition> byId(String mapId) {
        return Optional.ofNullable(snapshot.byId.get(mapId));
    }

    /** 按 tag 过滤(注册序);无匹配返回空列表。 */
    public List<MapDefinition> byTag(String tag) {
        List<MapDefinition> matched = new ArrayList<>();
        for (MapDefinition map : snapshot.order) {
            if (map.tags().contains(tag)) {
                matched.add(map);
            }
        }
        return List.copyOf(matched);
    }

    /** 全部地图(注册序,不可变)。 */
    public List<MapDefinition> all() {
        return snapshot.order;
    }
}
