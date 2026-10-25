package com.hydrokarbs.engine.session;

/**
 * 成员离开原因(2.1 施工步骤 9;15-PaperMC 适配 §15.1 会话入口监听处理
 * 退出/被踢两个入口;进 {@code MemberLeft} 事件载荷)。
 *
 * <p>封闭枚举,新增值须过设计评审。命名是 2.1 文档的建议集。
 */
public enum LeaveReason {

    /** 主动退出(断线视同退出:即发 MemberLeft 并标记 DISCONNECTED 保留在册)。 */
    QUIT,
    /** 被踢。 */
    KICK
}
