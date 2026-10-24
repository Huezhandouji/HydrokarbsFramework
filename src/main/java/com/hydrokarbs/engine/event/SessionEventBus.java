package com.hydrokarbs.engine.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 会话事件总线:与 {@link MatchModeInstanceEventBus} 同一套骨架
 * (订阅/发布/逐订阅者异常隔离/清除),阶段 2 消费(施工计划 1.3)。
 *
 * <ul>
 *   <li>订阅登记:类型可赋值匹配;优先级降序;同级按订阅序;</li>
 *   <li>发布:同线程同步派发(派发中订阅者会改状态,异步派发等于数据竞争,§11.1);</li>
 *   <li>逐订阅者 try/catch 隔离:单个订阅者抛异常不中断其他订阅者,记 WARN 后继续(§11.1/§11.3);</li>
 *   <li>{@link #clear()}:会话解散时调用,清掉全部订阅。</li>
 * </ul>
 */
public final class SessionEventBus {

    private static final Logger LOG = Logger.getLogger(SessionEventBus.class.getName());

    /** 默认优先级(未指定时)。 */
    public static final int DEFAULT_PRIORITY = 0;

    /** 订阅登记项:seq 保证同优先级稳定次序(订阅序)。 */
    private record Registration<E extends SessionEvent>(Class<E> type, int priority, long seq,
                                                        Consumer<E> subscriber) {
    }

    private final List<Registration<?>> registrations = new ArrayList<>();
    private long nextSeq;

    /** 发布事件:同步派发给全部类型可赋值的订阅者(优先级降序、同级按订阅序)。 */
    public void publish(SessionEvent event) {
        if (event == null) {
            return;
        }
        for (Registration<?> registration : orderedSnapshot()) {
            if (!registration.type().isInstance(event)) {
                continue;
            }
            dispatch(registration, event);
        }
    }

    /** 单订阅者派发:isInstance 已过滤,cast 安全;异常隔离记 WARN 后继续。 */
    private <E extends SessionEvent> void dispatch(Registration<E> registration, SessionEvent event) {
        try {
            registration.subscriber().accept(registration.type().cast(event));
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    "会话事件订阅者异常(已隔离,事件类型 " + event.getClass().getName() + ")", t);
        }
    }

    /** 按事件类型订阅(默认优先级)。 */
    public <E extends SessionEvent> void subscribe(Class<E> type, Consumer<E> subscriber) {
        subscribe(type, DEFAULT_PRIORITY, subscriber);
    }

    /** 按事件类型 + 优先级订阅(优先级降序,同级按订阅序)。 */
    public <E extends SessionEvent> void subscribe(Class<E> type, int priority, Consumer<E> subscriber) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subscriber, "subscriber");
        registrations.add(new Registration<>(type, priority, nextSeq++, subscriber));
    }

    /** 清除全部订阅(会话终结时调用;幂等)。 */
    public void clear() {
        registrations.clear();
    }

    /** 快照 + 排序:派发中订阅者再订阅/退订都不破坏本轮(写时复制口径)。 */
    private List<Registration<?>> orderedSnapshot() {
        List<Registration<?>> snapshot = List.copyOf(registrations);
        if (snapshot.size() < 2) {
            return snapshot;
        }
        List<Registration<?>> ordered = new ArrayList<>(snapshot);
        ordered.sort(Comparator.comparingInt((Registration<?> r) -> r.priority()).reversed()
                .thenComparingLong(r -> r.seq()));
        return ordered;
    }
}
