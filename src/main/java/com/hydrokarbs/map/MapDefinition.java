package com.hydrokarbs.map;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.hydrokarbs.map.SpawnPoint;

/**
 * 地图声明:不可变 record(施工计划 1.2;12-代码骨架 §12.1 {@code map/})。
 *
 * <p>本阶段最小只读形状——字段随消费方扩充(阶段 4 的区域/交互点组件需要时补
 * {@code regions}、{@code interact-points} 等)、已有形状不改。
 * 纯静态值:★v1.2「地图交成品不交工厂」——直接注册建好的实例合法。
 *
 * <ul>
 *   <li>{@code id}——地图 id(注册表命名空间);</li>
 *   <li>{@code world}——世界模板 id;</li>
 *   <li>{@code tags}——地图标签(与模式侧 mapTags 按「不相交即不匹配」原则匹配);</li>
 *   <li>{@code spawns}——按用途分组的出生点表(如按组名取一列坐标)。</li>
 * </ul>
 */
public record MapDefinition(String id, String world, Set<String> tags,
                            Map<String, List<SpawnPoint>> spawns) {

    public MapDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        // 外层与每组的列表都做不可变快照(纯静态值的结构保证)
        Map<String, List<SpawnPoint>> copy = new LinkedHashMap<>();
        Objects.requireNonNull(spawns, "spawns")
                .forEach((key, points) -> copy.put(key, List.copyOf(points)));
        spawns = Map.copyOf(copy);
    }

    /** 按用途键取出生点列表:键不存在返回空列表,不抛。 */
    public List<SpawnPoint> spawns(String key) {
        List<SpawnPoint> points = spawns.get(key);
        return points != null ? points : List.of();
    }
}
