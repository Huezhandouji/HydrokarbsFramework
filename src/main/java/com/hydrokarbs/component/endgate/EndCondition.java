package com.hydrokarbs.component.endgate;

import java.util.Optional;

import com.hydrokarbs.engine.instance.MatchModeInstanceView;

/**
 * 纯函数判定器:给定实例视图,返回判定(03-会话与模式切换 §3.4)。
 *
 * <p>数据不是组件——由 {@link EndGateComponent} 持有并求值;可离线单测。
 * 引擎不认识本接口;不想要结束判定的模式不挂结束门组件。
 */
public interface EndCondition {

    /** 求值优先级:降序,同 tick 多条命中取高者。 */
    int priority();

    /** 求值:返回 empty 表示本条未命中。 */
    Optional<GateVerdict> evaluate(MatchModeInstanceView ctx);
}
