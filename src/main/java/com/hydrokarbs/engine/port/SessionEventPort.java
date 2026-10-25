package com.hydrokarbs.engine.port;

import java.util.function.Consumer;

import com.hydrokarbs.engine.event.SessionEvent;

/**
 * 会话事件端口:会话组件对会话事件总线的读写面(★v4;02-核心概念 §2.5.1
 * {@code SessionPort#events()} 行;形状对齐实例级
 * {@link com.hydrokarbs.engine.event.MatchModeInstanceEventPort}:publish/subscribe)。
 *
 * <p>订阅句柄随组件 {@code stop} 自动回收:实现体把订阅句柄登记到<b>发起组件</b>名下
 * (调用栈反查,与实例容器的 later 登记同法),dissolve/隔离时框架统一注销;
 * 返回的 {@link AutoCloseable} 供组件显式提前注销(幂等,不抛)。
 *
 * <p>★ 待裁决登记(①,施工计划 2.6「要点」末条):本类型在 §12.2 v4 骨架中经
 * {@code SessionPort#events()} 引用,但 §12.1 包结构未单列——本版按主指令落
 * {@code engine/port/}(与五类型同住),「位置与形状」提请上游裁决,未静默决定。
 */
public interface SessionEventPort {

    /** 发布会话事件(主线程同步派发,逐订阅者异常隔离)。 */
    void publish(SessionEvent event);

    /** 按事件类型订阅(默认优先级);返回句柄(close 注销,幂等)。 */
    <E extends SessionEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> subscriber);

    /** 按事件类型 + 优先级订阅(优先级降序,同级按订阅序);返回句柄(close 注销,幂等)。 */
    <E extends SessionEvent> AutoCloseable subscribe(Class<E> type, int priority, Consumer<E> subscriber);
}
