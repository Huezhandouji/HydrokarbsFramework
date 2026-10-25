package com.hydrokarbs.map;

import java.util.Set;

/**
 * 具名三维区域(15-适配 §15.2 地图文件 {@code regions.*} 三形;施工计划 2.2)。
 *
 * <p>一个 record 承载三种形状,由 {@link RegionType} 区分:
 * <ul>
 *   <li>{@code CUBOID}——长方体({@code min}/{@code max} 对角顶点);</li>
 *   <li>{@code CYLINDER}——圆柱({@code center} 底面圆心、{@code radius} 底面半径、
 *       {@code height} 高,轴向为 y 轴);</li>
 *   <li>{@code SPHERE}——球体({@code center} 球心、{@code radius} 半径)。</li>
 * </ul>
 *
 * <p>纯几何、纯数据:零 Bukkit 依赖({@code World}/{@code Location} 换算推迟到消费点),
 * 坐标分量以 {@code double[]} 防御拷贝持有。静态工厂({@link #cuboid}/{@link #cylinder}/
 * {@link #sphere})是唯一建形入口——按形状校验必需字段,防「形状与字段不符」的坏声明。
 */
public record NamedRegion(String id, RegionType type, double[] min, double[] max,
                          double[] center, double radius, double height, Set<String> tags) {

    /** 区域形状三形(§15.2 地图文件 {@code regions.<id>.type})。 */
    public enum RegionType { CUBOID, CYLINDER, SPHERE }

    public NamedRegion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("区域 id 不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("区域 " + id + " 缺少形状声明");
        }
        switch (type) {
            case CUBOID -> {
                requireFinite(min, id, "min");
                requireFinite(max, id, "max");
            }
            case CYLINDER -> {
                requireFinite(center, id, "center");
                if (!Double.isFinite(radius) || radius < 0) {
                    throw new IllegalArgumentException("区域 " + id + " 的 radius 非法: " + radius);
                }
                if (!Double.isFinite(height) || height < 0) {
                    throw new IllegalArgumentException("区域 " + id + " 的 height 非法: " + height);
                }
            }
            case SPHERE -> {
                requireFinite(center, id, "center");
                if (!Double.isFinite(radius) || radius < 0) {
                    throw new IllegalArgumentException("区域 " + id + " 的 radius 非法: " + radius);
                }
            }
        }
        min = min == null ? null : min.clone();
        max = max == null ? null : max.clone();
        center = center == null ? null : center.clone();
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    /** 长方体区域({@code min}/{@code max} 为对角顶点)。 */
    public static NamedRegion cuboid(String id, double[] min, double[] max, Set<String> tags) {
        return new NamedRegion(id, RegionType.CUBOID, min, max, null, 0.0, 0.0, tags);
    }

    /** 圆柱区域({@code center} 底面圆心、轴向 y、向上 {@code height})。 */
    public static NamedRegion cylinder(String id, double[] center, double radius, double height,
                                       Set<String> tags) {
        return new NamedRegion(id, RegionType.CYLINDER, null, null, center, radius, height, tags);
    }

    /** 球形区域。 */
    public static NamedRegion sphere(String id, double[] center, double radius, Set<String> tags) {
        return new NamedRegion(id, RegionType.SPHERE, null, null, center, radius, 0.0, tags);
    }

    /**
     * 纯几何包含判定:{@code xyz} 为 {@code [x, y, z]} 三分量。边界值(恰在面上/面上)算包含。
     * 数组长度不足 3 或形状字段残缺(不可能经静态工厂出现)返回 false,不抛。
     */
    public boolean contains(double[] xyz) {
        if (xyz == null || xyz.length < 3) {
            return false;
        }
        double x = xyz[0];
        double y = xyz[1];
        double z = xyz[2];
        return switch (type) {
            case CUBOID -> within(x, min[0], max[0]) && within(y, min[1], max[1])
                    && within(z, min[2], max[2]);
            case CYLINDER -> {
                double dx = x - center[0];
                double dz = z - center[2];
                yield dx * dx + dz * dz <= radius * radius
                        && y >= center[1] && y <= center[1] + height;
            }
            case SPHERE -> {
                double dx = x - center[0];
                double dy = y - center[1];
                double dz = z - center[2];
                yield dx * dx + dy * dy + dz * dz <= radius * radius;
            }
        };
    }

    private static boolean within(double v, double a, double b) {
        return v >= Math.min(a, b) && v <= Math.max(a, b);
    }

    private static void requireFinite(double[] point, String id, String field) {
        if (point == null || point.length < 3) {
            throw new IllegalArgumentException("区域 " + id + " 的 " + field + " 缺少三分量");
        }
        for (double v : point) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("区域 " + id + " 的 " + field + " 含非有限值: " + v);
            }
        }
    }
}
