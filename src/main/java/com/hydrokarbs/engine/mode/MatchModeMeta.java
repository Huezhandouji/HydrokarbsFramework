package com.hydrokarbs.engine.mode;

import java.util.Objects;

/**
 * 模式元数据:纯数据 record,无行为(02-核心概念 §2.3 第二个代码块)。
 *
 * <p>v2 的四个接口方法(是否记史/缺省去向/人数窗等)收敛到这里。
 * ★v3.2:入场策略字段已删——入场裁决是行为,归入场控制组件
 * (经引擎端口裁决,03-会话与模式切换 §3.2.1)。
 *
 * <p>★v1.7(25 号修正案):原首分量「所需外部能力标签」随能力标签机制
 * 全链退场删除——模式对外部能力的硬依赖改由会话组件桥接表达(类型化
 * {@code requires/requiresOptional} + 装配闸 + quarantine),字符串平行层不再存在;
 * 元数据由各模式工厂逐字段自建,引擎零内容词汇。
 */
public record MatchModeMeta(
        boolean recordsHistory,             // 是否写对局历史/统计
        int minParticipants,                // 人数窗下限
        int maxParticipants,                // 人数窗上限
        DefaultTransition defaultTransition // 无显式去向时的缺省去向(纯数据,无策略对象)
) {

    public MatchModeMeta {
        Objects.requireNonNull(defaultTransition, "defaultTransition");
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
