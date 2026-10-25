package com.hydrokarbs.map;

/**
 * 交互点声明(15-适配 §15.2 地图文件 {@code interact-points.*};施工计划 2.2)。
 *
 * <p>纯数据:{@code pos} 为 {@code [x, y, z]} 三分量(防御拷贝)、{@code radius}
 * 为触发半径、{@code tag} 为交互类型标签(词汇由模式侧定义,地图侧透传)、
 * {@code anchor} 为挂靠声明(如交互面板/方块锚)。零 Bukkit 依赖。
 */
public record InteractPoint(String id, double[] pos, double radius, String tag, String anchor) {

    public InteractPoint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("交互点 id 不能为空");
        }
        if (pos == null || pos.length < 3) {
            throw new IllegalArgumentException("交互点 " + id + " 的 pos 缺少三分量");
        }
        if (!Double.isFinite(radius) || radius < 0) {
            throw new IllegalArgumentException("交互点 " + id + " 的 radius 非法: " + radius);
        }
        pos = pos.clone();
        tag = tag == null ? "" : tag;
        anchor = anchor == null ? "" : anchor;
    }
}
