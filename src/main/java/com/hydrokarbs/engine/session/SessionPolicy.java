package com.hydrokarbs.engine.session;

import java.util.Objects;

/**
 * 会话策略:拓扑/最短驻留/断线宽限(03-会话与模式切换 §3.5、§3.6;
 * 15-PaperMC 适配 §15.2 {@code session:} 块;2.1 施工步骤 6)。
 *
 * <p>纯数据;从 {@code config.yml} 构造发生在 L0 装配。语义:
 * <ul>
 *   <li>{@code topology}:部署拓扑由宿主经 {@code SessionService.create} 次数表达
 *       (§3.6 引擎零感知),本字段无框架行为消费(仅 create 日志回显),
 *       装配固定 {@link Topology#ROTATION} 占位;</li>
 *   <li>{@code minDwellTicks} 默认 100(§3.5 切换防护①);</li>
 *   <li>宽限秒与 {@link ConnectionTracker} 同源。</li>
 * </ul>
 *
 * <p>空场策略住宿级组件 {@code component/session/EmptyArenaComponent}(§6.4);
 * <b>连接追踪与宽限 sweep 仍内核</b>。
 */
public record SessionPolicy(Topology topology, int minDwellTicks, int disconnectGraceSeconds) {

    /** §15.2 {@code session:} 块默认值(单一落点,防魔法数散落 L0 装配)。 */
    public static final int DEFAULT_MIN_DWELL_TICKS = 100;
    public static final int DEFAULT_DISCONNECT_GRACE_SECONDS = 60;

    /** 部署拓扑:差别只在装配期,引擎零感知(§3.6)。封闭枚举,新增值须过设计评审。 */
    public enum Topology {
        /** 单会话轮换:常驻不解散(不挂空场组件)。 */
        ROTATION,
        /** 多竞技场并行:挂空场策略组件,空场到时自动解散。 */
        ARENA
    }

    public SessionPolicy {
        Objects.requireNonNull(topology, "topology");
        if (minDwellTicks < 0) {
            throw new IllegalArgumentException("minDwellTicks 不得为负: " + minDwellTicks);
        }
        if (disconnectGraceSeconds < 0) {
            throw new IllegalArgumentException("disconnectGraceSeconds 不得为负: " + disconnectGraceSeconds);
        }
    }

    /** §15.2 默认值便捷工厂(topology 由调用方显式给)。 */
    public static SessionPolicy defaults(Topology topology) {
        return new SessionPolicy(topology, DEFAULT_MIN_DWELL_TICKS, DEFAULT_DISCONNECT_GRACE_SECONDS);
    }

    /** 断线宽限(毫秒换算,与 {@link ConnectionTracker} 同源)。 */
    public long disconnectGraceMillis() {
        return disconnectGraceSeconds * 1000L;
    }
}
