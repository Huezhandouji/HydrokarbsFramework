package com.hydrokarbs.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 区域集:一张地图声明的全部 {@link NamedRegion} 的不可变视图(施工计划 2.2;
 * 12-代码骨架 §12.1 {@code map/})。
 *
 * <p>两个查询口——按 id 查({@link #byId})、按 tag 过滤({@link #byTag});
 * 全集经 {@link #all()}(声明序)。构造后不可变:热重载换图时整体重建,不做逐条增删。
 */
public final class RegionSet {

    private static final RegionSet EMPTY = new RegionSet(List.of());

    private final Map<String, NamedRegion> byId;

    private RegionSet(List<NamedRegion> regions) {
        Map<String, NamedRegion> index = new LinkedHashMap<>();
        for (NamedRegion region : regions) {
            index.put(region.id(), region);
        }
        this.byId = Map.copyOf(index);
    }

    /** 空区域集(缺 {@code regions} 段的地图用它)。 */
    public static RegionSet empty() {
        return EMPTY;
    }

    /** 按声明序建立不可变区域集。 */
    public static RegionSet of(List<NamedRegion> regions) {
        return new RegionSet(List.copyOf(regions));
    }

    /** 按 id 查区域;无此 id 返回 empty。 */
    public Optional<NamedRegion> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** 按 tag 过滤(声明序);无匹配返回空列表。 */
    public List<NamedRegion> byTag(String tag) {
        List<NamedRegion> matched = new ArrayList<>();
        for (NamedRegion region : byId.values()) {
            if (region.tags().contains(tag)) {
                matched.add(region);
            }
        }
        return List.copyOf(matched);
    }

    /** 全部区域(声明序,不可变)。 */
    public List<NamedRegion> all() {
        return List.copyOf(byId.values());
    }

    /** 区域数(诊断/日志用)。 */
    public int size() {
        return byId.size();
    }
}
