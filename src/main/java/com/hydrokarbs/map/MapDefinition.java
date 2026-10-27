package com.hydrokarbs.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.hydrokarbs.engine.port.ResetStrategy;

/**
 * 地图声明:不可变 record(施工计划 1.2 立最小形状、2.2 扩全量;12-代码骨架 §12.1
 * {@code map/})。
 *
 * <p>字段来源 = 注册声明(纯静态值,★「地图交成品不交工厂」——调用方建好实例后
 * 经 {@code MapRegistry#register}/api {@code registerMap} 直接注册;声明纯静态、
 * 无任何玩法规则字段,§15.2 分工)。★文件式装载通道({@code maps/<id>.yml} 逐键
 * 解析)经用户裁决删除,装载层解析口 {@code from(Map,String)} 与其校验随之移除;
 * 结构合法性由调用方在构造期自理(响亮抛纪律在注册口:模板目录/查重,MapRegistry)。
 * 零 Bukkit 依赖:{@code World}/{@code Location} 换算推迟到消费点。
 */
public record MapDefinition(String id, String displayName, String world, Set<String> tags,
                            Bounds bounds, Map<String, List<SpawnPoint>> spawns, RegionSet regions,
                            List<InteractPoint> interactPoints, Map<String, Object> metadata,
                            ResetStrategy resetStrategy) {

    public MapDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(spawns, "spawns");
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(interactPoints, "interactPoints");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(resetStrategy, "resetStrategy");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        tags = Set.copyOf(tags);
        Map<String, List<SpawnPoint>> copy = new LinkedHashMap<>();
        spawns.forEach((key, points) -> copy.put(key, List.copyOf(points)));
        spawns = Map.copyOf(copy);
        interactPoints = List.copyOf(interactPoints);
        metadata = deepImmutable(metadata);
    }

    /** 阶段 1 的四参最小形状(1.2 起既有构造点兼容):无边界/无区域/无交互点/无元数据。 */
    public MapDefinition(String id, String world, Set<String> tags,
                         Map<String, List<SpawnPoint>> spawns) {
        this(id, id, world, tags, null, spawns, RegionSet.empty(), List.of(), Map.of(),
                ResetStrategy.REUSE_DIRTY);
    }

    /** 按用途键取出生点列表:键不存在返回空列表,不抛。 */
    public List<SpawnPoint> spawns(String key) {
        List<SpawnPoint> points = spawns.get(key);
        return points != null ? points : List.of();
    }

    /** 地图边界(可空 = 未声明;min/max 为对角顶点)。 */
    public record Bounds(double[] min, double[] max) {

        public Bounds {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            min = min.clone();
            max = max.clone();
        }
    }

    // ── 纯静态值纪律:metadata 透传不解释,模型整体不可变 ─────────────

    /** 递归不可变拷贝(metadata 透传不解释,但模型整体保持纯静态值纪律)。 */
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), immutable(entry.getValue()));
            }
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(immutable(item)));
            return List.copyOf(copy);
        }
        return value;
    }

    private static Map<String, Object> deepImmutable(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), immutable(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
