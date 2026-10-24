package com.hydrokarbs.map;

/**
 * 出生点坐标(15-PaperMC 适配 §15.2 地图文件 spawns 形状)。
 * 纯数据 record,yaw 为水平朝向角。
 */
public record SpawnPoint(double x, double y, double z, float yaw) {
}
