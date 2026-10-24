package com.hydrokarbs.engine.session;

import java.util.Optional;

/**
 * 交接载荷的只读面(02-核心概念 §2.5「入口交接载荷(只读)」)。
 *
 * <p>{@code MatchModeInstancePort#handoff()} 的返回类型:实例组件经
 * {@code svc().self().handoff()} 读入口交接载荷时拿到的是本只读视图——
 * 没有写口。写侧只有切换出局的 {@code onHandoff(Handoff.Builder out)} 钩子。
 */
public interface HandoffView {

    /** 取值:键不存在或类型不符返回 empty,不抛(14-陷阱清单 §14.20-②)。 */
    <T> Optional<T> get(Handoff.Key<T> key);

    /** 是否存在该键(不论取值类型)。 */
    boolean has(Handoff.Key<?> key);
}
