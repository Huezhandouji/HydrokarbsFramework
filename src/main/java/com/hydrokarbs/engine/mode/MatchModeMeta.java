package com.hydrokarbs.engine.mode;

/**
 * 模式元数据:纯数据 record,无行为(02-核心概念 §2.3 第二个代码块)。
 *
 * <p>v2 的接口方法(是否记史/人数窗等)收敛到这里。
 * ★v3.2:入场策略字段已删——入场裁决是行为,归入场控制组件
 * (经引擎端口裁决,03-会话与模式切换 §3.2.1)。
 *
 * <p>★v1.7(25 号修正案):原首分量「所需外部能力标签」随能力标签机制
 * 全链退场删除——模式对外部能力的硬依赖改由会话组件桥接表达(类型化
 * {@code requires/requiresOptional} + 装配闸 + quarantine),字符串平行层不再存在;
 * 元数据由各模式工厂逐字段自建,引擎零内容词汇。
 *
 * <p>★v1.13(31 号修正案):缺省去向分量与嵌套枚举 {@code DefaultTransition}
 * 随缺省去向管线整体退场删除——无显式去向的结束一律回落
 * {@code session.fallback-mode}(默认 = 出厂空模式兜底);三值去向词汇
 * (FALLBACK/STAY/ROTATION)自冻结面清除,消费过该分量的旧编译物需按
 * API_VERSION 8 重编译(经用户裁决不设双轨过渡期)。
 */
public record MatchModeMeta(
        boolean recordsHistory,             // 是否写对局历史/统计
        int minParticipants,                // 人数窗下限
        int maxParticipants                 // 人数窗上限
) {
}
