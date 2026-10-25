package com.hydrokarbs.engine.session;

/**
 * 会话解散原因(2.1 施工步骤 8;SHUTDOWN 出处见 12-代码骨架 §12.2
 * {@code EngineTicker#close} 的关停驱动;其余对应 §3.1 的三个触发源与 §3.2 二级回退)。
 *
 * <p>封闭枚举,进事件载荷({@code SessionDissolved})与日志;新增值须过设计评审。
 * 命名是 2.1 文档的建议集(设计案未枚举全,已登记待裁决)——★ 2.6 回填(计划内修订,
 * 记 deviations):原 {@code EMPTY_TIMEOUT} 更名为 {@code EMPTY},对齐变更指示 D11/
 * 03 §3.8/06 §6.4 的统一词汇(空场策略下放 {@code EmptyArenaComponent} 后,
 * 解散原因即「空场」而非「空场超时计时器」);其余值不变。
 */
public enum DissolveReason {

    /** 空场(arena 拓扑,由空场策略组件请求;原 EMPTY_TIMEOUT,2.6 更名)。 */
    EMPTY,
    /** 管理命令。 */
    ADMIN,
    /** 服务端/插件关停(§12.2 onDisable 驱动)。 */
    SHUTDOWN,
    /** 切换三级回退的二级:fallback 模式也装配失败,解散兜底(§3.2)。 */
    FALLBACK_DISSOLVE
}
