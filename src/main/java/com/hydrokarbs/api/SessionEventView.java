package com.hydrokarbs.api;

import java.util.UUID;

import com.hydrokarbs.engine.session.SessionId;

/**
 * 会话事件快照族(★v1.1-D3;07-公共API §7.1 第二代码块;7.5 施工文档步骤 3):
 * 不可变 record,八事件各一(Created/MemberJoined/MemberLeft/MemberReconnected/
 * ModeSwitchStarted/ModeSwitchCompleted/Dissolved/ComponentQuarantined)——
 * 与引擎侧 {@code com.hydrokarbs.engine.event.SessionEvent} 八事件一一对应。
 *
 * <p><b>快照纪律</b>:全部是不可变值,<b>不含活会话/活组件引用</b>(镜像 ★29
 * 「现取不缓存」纪律;07 §7.1-D3 边界);枚举载荷存 {@code name()} 字符串
 * ({@code LeaveReason}/{@code DissolveReason}——冻结面不被引擎枚举演进绑死,
 * §0.5.3 末行拒绝码收缩先例);{@code SessionId} 直接用
 * {@code engine.session.SessionId}(07 §7.2 白名单值类型,7.5 决策 13)。
 *
 * <p><b>{@code tick} 字段口径(7.5 决策 13)</b>:承载引擎事件的
 * {@code occurredAtMillis}(发布时刻,毫秒时间戳)——名字沿用 07 §7.1 骨架,
 * 语义是墙钟毫秒而非引擎 tick 计数,字段名以 {@code tick} 落定、javadoc 注明。
 *
 * <p><b>{@code ModeSwitchCompleted#ok} 口径</b>:引擎当前仅在切换成功
 * (执行段 B5 落位)后发布完成事件,失败路径以拒绝 future / 回退解散呈现——
 * 本面照 07 §7.1 骨架保留 {@code ok} 字段,快照恒 {@code true}(字段在册,
 * 语义演进留位;不新增引擎事件载荷,2.1 冻结词汇与载荷不动)。
 *
 * <p><b>顺序承诺(D3)</b>:单会话内事件有序;跨插件订阅者间不承诺送达顺序。
 * 实例事件({@code MatchModeInstanceEvent})不经本面暴露(19-§7-Q5 维持不开放)。
 */
public sealed interface SessionEventView {

    /** 事件时间戳(毫秒;引擎发布时刻 {@code occurredAtMillis}——字段名 {@code tick} 见接口注)。 */
    long tick();

    /** 会话创建完成(引擎 {@code SessionCreated};初始模式 id 不入快照——快照经 {@code session(id)} 现取)。 */
    record Created(SessionId session, long tick) implements SessionEventView {
    }

    /** 成员加入名册(引擎 {@code MemberJoined})。 */
    record MemberJoined(SessionId session, UUID player, long tick) implements SessionEventView {
    }

    /**
     * 成员离开(引擎 {@code MemberLeft};{@code reason} = {@link com.hydrokarbs.engine.session.LeaveReason}
     * 的 {@code name()}:QUIT/KICK——宽限期满移除不发本事件,2.1 要点口径)。
     */
    record MemberLeft(SessionId session, UUID player, String reason, long tick)
            implements SessionEventView {
    }

    /** 断线成员宽限期内重连(引擎 {@code MemberReconnected})。 */
    record MemberReconnected(SessionId session, UUID player, long tick) implements SessionEventView {
    }

    /** 模式切换开始(引擎 {@code ModeSwitchStarted};from/to 为模式 id,空串 = 无实例)。 */
    record ModeSwitchStarted(SessionId session, String from, String to, long tick)
            implements SessionEventView {
    }

    /** 模式切换完成(引擎 {@code ModeSwitchCompleted};{@code ok} 口径见接口注——快照恒 true)。 */
    record ModeSwitchCompleted(SessionId session, String from, String to, boolean ok, long tick)
            implements SessionEventView {
    }

    /**
     * 会话解散(引擎 {@code SessionDissolved};{@code reason} =
     * {@link com.hydrokarbs.engine.session.DissolveReason} 的 {@code name()})。
     */
    record Dissolved(SessionId session, String reason, long tick) implements SessionEventView {
    }

    /**
     * 会话组件故障隔离(引擎 {@code SessionComponentQuarantined};D9/§3.8——
     * 组件停用、会话不解散;{@code cause} = 引擎事件的 {@code reason})。
     */
    record ComponentQuarantined(SessionId session, String componentId, String cause, long tick)
            implements SessionEventView {
    }
}
