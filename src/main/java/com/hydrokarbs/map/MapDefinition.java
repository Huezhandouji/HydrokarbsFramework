package com.hydrokarbs.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.hydrokarbs.engine.port.ResetStrategy;

/**
 * 地图声明:不可变 record(施工计划 1.2 立最小形状、2.2 扩全量;12-代码骨架 §12.1
 * {@code map/})。
 *
 * <p>字段来源 = 15-适配 §15.2 地图文件({@code maps/<id>.yml})逐键:
 * {@code id}/{@code display-name}/{@code world}/{@code tags}/{@code bounds.min-max}/
 * {@code spawns.<组名>}/{@code regions.*}/{@code interact-points.*}/{@code metadata.*}/
 * {@code reset-strategy}。纯静态值:★v1.2「地图交成品不交工厂」——直接注册建好的
 * 实例合法;地图文件纯静态、无任何玩法规则字段(§15.2 分工)。
 *
 * <p>静态 {@link #from(Map, String)} 是「装载层数据 → 模型」的唯一解析口:缺
 * {@code id}/{@code world}/{@code tags} 等结构缺失抛 {@link IllegalArgumentException}
 * (消息带文件名,响亮——§15.2 铁律 3「局部失败」:单个坏文件整体跳过、不瘫全服);
 * 值级软容错(可转换的尽量转换,{@code display-name} 缺省 = id、
 * {@code reset-strategy} 缺省/非法 = {@link ResetStrategy#REUSE_DIRTY})。
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

    // ── 装载层 Map → 模型(唯一解析口;被 bridge/config 的编解码器调用)──────

    /**
     * 从已解析的 YAML 根映射建模型。结构缺失(缺 {@code id}/{@code world}/{@code tags}、
     * 区域形状残缺、出生点坐标残缺、{@code bounds} 只有单边)抛
     * {@link IllegalArgumentException},消息带文件名与定位信息——由调用方
     * (文件编解码器)包装为装载异常并按「单文件失败跳过」处置(§15.2 铁律 3)。
     *
     * @param root     YAML 根映射(可为 null/空 = 空文件)
     * @param fileName 文件名(仅用于异常消息)
     */
    public static MapDefinition from(Map<String, Object> root, String fileName) {
        if (root == null || root.isEmpty()) {
            throw bad(fileName, null, "缺少 id(空文件不是合法的地图声明)");
        }
        String id = requireText(root.get("id"), fileName, "id");
        String world = requireText(root.get("world"), fileName, "world");
        if (!(root.get("tags") instanceof List<?> rawTags)) {
            throw bad(fileName, null, "缺少 tags(地图标签是必填键)");
        }
        Set<String> tags = new LinkedHashSet<>();
        for (Object tag : rawTags) {
            tags.add(String.valueOf(tag));
        }
        String displayName = root.get("display-name") == null ? id
                : String.valueOf(root.get("display-name"));
        Bounds bounds = parseBounds(root.get("bounds"), fileName);
        Map<String, List<SpawnPoint>> spawns = parseSpawns(root.get("spawns"), fileName);
        RegionSet regions = parseRegions(root.get("regions"), fileName);
        List<InteractPoint> interactPoints = parseInteractPoints(root.get("interact-points"), fileName);
        Map<String, Object> metadata = root.get("metadata") instanceof Map<?, ?> rawMeta
                ? deepImmutable(rawMeta)
                : Map.of();
        ResetStrategy reset = root.get("reset-strategy") == null ? ResetStrategy.REUSE_DIRTY
                : parseResetStrategy(String.valueOf(root.get("reset-strategy")));
        return new MapDefinition(id, displayName, world, tags, bounds, spawns, regions,
                interactPoints, metadata, reset);
    }

    private static Bounds parseBounds(Object raw, String fileName) {
        if (!(raw instanceof Map<?, ?> boundsMap)) {
            return null;                                  // 整段缺席 = 未声明,合法
        }
        return new Bounds(point(boundsMap.get("min"), fileName, "bounds.min"),
                point(boundsMap.get("max"), fileName, "bounds.max"));
    }

    private static Map<String, List<SpawnPoint>> parseSpawns(Object raw, String fileName) {
        Map<String, List<SpawnPoint>> result = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> groups)) {
            return result;
        }
        for (Map.Entry<?, ?> group : groups.entrySet()) {
            String groupName = String.valueOf(group.getKey());
            if (!(group.getValue() instanceof List<?> points)) {
                throw bad(fileName, groupName, "出生点组必须是列表");
            }
            List<SpawnPoint> parsed = new ArrayList<>();
            for (Object entry : points) {
                if (!(entry instanceof Map<?, ?> pointMap)) {
                    throw bad(fileName, groupName, "出生点必须是 x/y/z 映射");
                }
                Double x = number(pointMap.get("x"));
                Double y = number(pointMap.get("y"));
                Double z = number(pointMap.get("z"));
                if (x == null || y == null || z == null) {
                    throw bad(fileName, groupName, "出生点缺少 x/y/z 分量");
                }
                Double yaw = number(pointMap.get("yaw"));
                parsed.add(new SpawnPoint(x, y, z, yaw == null ? 0f : yaw.floatValue()));
            }
            result.put(groupName, parsed);
        }
        return result;
    }

    private static RegionSet parseRegions(Object raw, String fileName) {
        List<NamedRegion> parsed = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> regions)) {
            return RegionSet.empty();
        }
        for (Map.Entry<?, ?> entry : regions.entrySet()) {
            String regionId = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> region)) {
                throw bad(fileName, regionId, "区域声明必须是映射");
            }
            String typeText = region.get("type") == null ? null : String.valueOf(region.get("type"));
            NamedRegion.RegionType type = parseRegionType(typeText);
            if (type == null) {
                throw bad(fileName, regionId, "缺少或非法的区域形状 type: " + typeText);
            }
            Set<String> tags = new LinkedHashSet<>();
            if (region.get("tags") instanceof List<?> rawTags) {
                rawTags.forEach(tag -> tags.add(String.valueOf(tag)));
            }
            NamedRegion result = switch (type) {
                case CUBOID -> NamedRegion.cuboid(regionId,
                        point(region.get("min"), fileName, "regions." + regionId + ".min"),
                        point(region.get("max"), fileName, "regions." + regionId + ".max"), tags);
                case CYLINDER -> NamedRegion.cylinder(regionId,
                        point(region.get("center"), fileName, "regions." + regionId + ".center"),
                        positive(region.get("radius"), fileName, regionId, "radius"),
                        nonNegative(region.get("height"), fileName, regionId, "height"), tags);
                case SPHERE -> NamedRegion.sphere(regionId,
                        point(region.get("center"), fileName, "regions." + regionId + ".center"),
                        nonNegative(region.get("radius"), fileName, regionId, "radius"), tags);
            };
            parsed.add(result);
        }
        return RegionSet.of(parsed);
    }

    private static List<InteractPoint> parseInteractPoints(Object raw, String fileName) {
        List<InteractPoint> parsed = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> points)) {
            return parsed;
        }
        for (Map.Entry<?, ?> entry : points.entrySet()) {
            String pointId = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> point)) {
                throw bad(fileName, pointId, "交互点声明必须是映射");
            }
            parsed.add(new InteractPoint(pointId,
                    point(point.get("pos"), fileName, "interact-points." + pointId + ".pos"),
                    nonNegative(point.get("radius"), fileName, pointId, "radius"),
                    point.get("tag") == null ? "" : String.valueOf(point.get("tag")),
                    point.get("anchor") == null ? "" : String.valueOf(point.get("anchor"))));
        }
        return parsed;
    }

    // ── 值转换小件(软容错:能转尽转) ───────────────────────────────

    private static String requireText(Object raw, String fileName, String key) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw bad(fileName, null, "缺少 " + key + "(必填键)");
        }
        return String.valueOf(raw);
    }

    private static double[] point(Object raw, String fileName, String where) {
        if (!(raw instanceof List<?> list) || list.size() < 3) {
            throw bad(fileName, where, "需要 [x, y, z] 三分量");
        }
        double[] xyz = new double[3];
        for (int i = 0; i < 3; i++) {
            Double v = number(list.get(i));
            if (v == null) {
                throw bad(fileName, where, "坐标分量不是数值");
            }
            xyz[i] = v;
        }
        return xyz;
    }

    private static Double number(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double nonNegative(Object raw, String fileName, String owner, String field) {
        Double v = number(raw);
        if (v == null || v < 0 || !Double.isFinite(v)) {
            throw bad(fileName, owner, field + " 需要非负数值: " + raw);
        }
        return v;
    }

    private static double positive(Object raw, String fileName, String owner, String field) {
        double v = nonNegative(raw, fileName, owner, field);
        if (v == 0) {
            throw bad(fileName, owner, field + " 必须大于 0");
        }
        return v;
    }

    private static NamedRegion.RegionType parseRegionType(String text) {
        if (text == null) {
            return null;
        }
        try {
            return NamedRegion.RegionType.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ResetStrategy parseResetStrategy(String text) {
        try {
            return ResetStrategy.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ResetStrategy.REUSE_DIRTY;             // 值级软容错:非法值降级默认
        }
    }

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

    private static IllegalArgumentException bad(String fileName, String where, String detail) {
        String at = where == null ? "" : "(" + where + ")";
        return new IllegalArgumentException("地图文件 " + fileName + at + ": " + detail);
    }
}
