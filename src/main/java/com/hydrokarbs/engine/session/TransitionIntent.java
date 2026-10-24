package com.hydrokarbs.engine.session;

/**
 * 转移意图:「下一站去哪」的指令(03-会话与模式切换 §3.4 原样)。
 *
 * <p>由结束判定组件的判定器或外部调用方产出,经 {@link EndResult} 携带交给引擎。
 * 纯数据,无行为。
 */
public record TransitionIntent(MatchModeSelection target, Handoff payload) {
}
