package com.hydrokarbs.engine.event;

import java.util.function.Consumer;

/**
 * 实例事件端口:组件对实例事件总线的读写面(02-核心概念 §2.5 {@code events()} 行)。
 *
 * <p>「按事件类型 + 优先级调用订阅者」(11-事件流 §11.1);订阅随组件 stop 自动回收。
 * 实现体是 {@code MatchModeInstanceEventBus}(主线程同步派发、逐订阅者异常隔离)。
 *
 * <p><b>占位:1.3 按其文档接管</b>(签名已由施工计划 1.3 钉定)。
 * 本阶段因 {@code MatchModeInstancePort#events()} 的签名引用而先行落位。
 */
public interface MatchModeInstanceEventPort {

    /** 发布事件(主线程同步派发)。 */
    void publish(MatchModeInstanceEvent event);

    /** 按事件类型订阅(默认优先级)。 */
    <E extends MatchModeInstanceEvent> void subscribe(Class<E> type, Consumer<E> subscriber);

    /** 按事件类型 + 优先级订阅(优先级降序,同级按订阅序)。 */
    <E extends MatchModeInstanceEvent> void subscribe(Class<E> type, int priority, Consumer<E> subscriber);
}
