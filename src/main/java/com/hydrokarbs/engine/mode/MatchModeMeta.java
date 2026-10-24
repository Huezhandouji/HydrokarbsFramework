package com.hydrokarbs.engine.mode;

import java.util.Objects;
import java.util.Set;

/**
 * 模式元数据:纯数据 record,无行为(02-核心概念 §2.3 第二个代码块)。
 *
 * <p>v2 的四个接口方法(能力要求/是否记史/缺省去向/人数窗)收敛到这里。
 * ★v3.2:入场策略字段已删——入场裁决是行为,归入场控制组件
 * (经引擎端口裁决,03-会话与模式切换 §3.2.1)。
 *
 * <p>★v3.1:不提供任何预设常量与预设能力标签——预设会把内容词汇写进引擎,
 * 违反「框架不认识内容」;元数据由各模式工厂逐字段自建。
 * 能力标签是字符串集合,词汇由提供方注册,引擎零具体标签。
 */
public record MatchModeMeta(
        Set<String> requiredCapabilities,   // 所需外部能力标签;空 = 无依赖
        boolean recordsHistory,             // 是否写对局历史/统计
        int minParticipants,                // 人数窗下限
        int maxParticipants,                // 人数窗上限
        DefaultTransition defaultTransition // 无显式去向时的缺省去向(纯数据,无策略对象)
) {

    public MatchModeMeta {
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        Objects.requireNonNull(defaultTransition, "defaultTransition");
        requiredCapabilities = Set.copyOf(requiredCapabilities);
    }

    /** 缺省去向:回退(切到会话配置的回退模式)/ 原地 / 轮换(读配置轮换表)。 */
    public enum DefaultTransition {
        /** 切到会话配置的回退模式(引擎不认识任何具体模式 id)。 */
        FALLBACK,
        /** 原地再开一局同模式。 */
        STAY,
        /** 读配置轮换表依次循环。 */
        ROTATION
    }
}
