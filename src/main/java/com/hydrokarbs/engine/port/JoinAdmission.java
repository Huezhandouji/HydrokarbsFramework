package com.hydrokarbs.engine.port;

import java.util.UUID;

/**
 * 实例入场裁决端口(★v3.2,03-会话与模式切换 §3.2.1 逐字)。
 *
 * <p>L3 组件可实现本接口;会话服务的 join 经当前实例<b>按组件声明序查询第一个实现者</b>。
 * 引擎只认识「裁决」这个机制结果({@link JoinDecision.Kind} 的 ADMIT/REJECT
 * 是结构词汇:对应收为参赛/拒绝两种处置),不认识任何具体入场策略——
 * 入场路由是行为(可能依赖阶段、人数、队伍状态),把行为编码进静态数据枚举正是
 * 设计案警惕的写法,故 v3.2 拆掉旧枚举下放为组件。
 *
 * <p><b>不挂任何实现 = 零配置即全接纳</b>(默认 ADMIT)——「不介入」的表达是不声明,
 * 与「不挂组件即不介入」的组件挂载口径同一。L3 侧的标准实现是入场控制组件
 * (按阶段门控的 ADMIT/REJECT 数据声明,§6.1-#24)。
 */
public interface JoinAdmission {

    /**
     * 裁决一名玩家的入场安置。
     *
     * @param player 待入场玩家
     * @param ctx    入场上下文(触发点 + 所属会话)
     * @return 裁决(ADMIT 参赛 / REJECT 拒收)
     */
    JoinDecision admit(UUID player, JoinContext ctx);
}
