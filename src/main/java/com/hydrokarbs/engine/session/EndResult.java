package com.hydrokarbs.engine.session;

import java.util.Optional;

/**
 * 引擎侧的结束货币:★v3.1 起只有「去向」、没有「剧情」(03-会话与模式切换 §3.4)。
 *
 * <p>仅携带可选的 {@link TransitionIntent}(下一站)。胜负、原因等结局词汇
 * 是玩法语义,归内容层(引擎不枚举「游戏有哪些结局」)。实例结束的唯一请求口是
 * {@code svc().self().end(result)}——结束是组件的主动声明,不被框架轮询。
 *
 * <p>无显式去向时回落 {@code session.fallback-mode} 配置的回退模式(31 号:
 * 缺省去向管线退场,默认 = 出厂空模式兜底);引擎自兜底结束(故障隔离)同样用
 * {@link #nowhere()}。
 */
public record EndResult(Optional<TransitionIntent> transition) {

    /** 无显式去向工厂:回落 session.fallback-mode(31 号;原「读模式缺省去向」口径退场)。 */
    public static EndResult nowhere() {
        return new EndResult(Optional.empty());
    }
}
