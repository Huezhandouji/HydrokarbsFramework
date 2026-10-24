package com.hydrokarbs.engine.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 引擎与组件共享的实例事件根(12-代码骨架 §12.2)。
 *
 * <p>★ 不是 Bukkit Event、不进服务端总线;★ 根接口开放——事件词汇跟着发布者走:
 * <ul>
 *   <li>引擎只定义自己发布的 {@link Structural} 封闭子族(参赛者/实例生命周期,
 *       全是引擎域词汇,零内容词汇);</li>
 *   <li>内容事件住各自组件包,直接实现本接口;</li>
 *   <li>模式自定义事件:record 实现本接口,或用 {@link Custom} 字符串类型载荷。</li>
 * </ul>
 *
 * <p>★ 实现注记(施工计划 1.3):本接口声明了 {@link #tick()} 与 {@link #occurredAtMillis()},
 * 所以每个事件 record 一律携带这两个组件;发布方(容器/组件)从实例时钟取值,
 * 不在事件里各自取系统时钟。{@link Custom#of(String)} 是 tick=0、millis=当前时刻的
 * 便利工厂,需要精确 tick 时用全参构造。
 */
public interface MatchModeInstanceEvent {

    /** 发布时的实例存活 tick 数。 */
    int tick();

    /** 发布时刻的系统毫秒时间戳(取自实例时钟)。 */
    long occurredAtMillis();

    /**
     * 类型可赋值匹配:引擎结构事件与组件包自定义事件统一经本方法匹配
     * (消费方 {@code ev.is(SomeType.class)};12-代码骨架 §12.2)。
     */
    default boolean is(Class<? extends MatchModeInstanceEvent> type) {
        return type.isInstance(this);
    }

    /** 结构事件:由引擎容器发布的参赛者/实例生命周期(引擎域词汇,封闭子族)。 */
    sealed interface Structural extends MatchModeInstanceEvent permits
            MatchModeInstanceStarted, MatchModeInstanceEnded, ParticipantJoined,
            ParticipantLeft, ParticipantSpawned, ParticipantKilled {
    }

    /** 实例启动完成(声明序 start 全部派发后由容器发布)。 */
    record MatchModeInstanceStarted(int tick, long occurredAtMillis) implements Structural {
    }

    /** 实例结束请求收口(去向词汇归结束货币,本事件只携带时间)。 */
    record MatchModeInstanceEnded(int tick, long occurredAtMillis) implements Structural {
    }

    /** 参赛者入册。 */
    record ParticipantJoined(int tick, long occurredAtMillis, UUID player) implements Structural {
    }

    /** 参赛者离册。 */
    record ParticipantLeft(int tick, long occurredAtMillis, UUID player) implements Structural {
    }

    /** 参赛者完成入场安置。 */
    record ParticipantSpawned(int tick, long occurredAtMillis, UUID player) implements Structural {
    }

    /**
     * 参赛者阵亡信号:只带 victim/killer/cause——玩法上下文(职能/分组等)由消费者
     * 经组件读口自查,引擎事件不携带内容字段。
     */
    record ParticipantKilled(int tick, long occurredAtMillis, UUID victim, UUID killer,
                             EntityDamageEvent.DamageCause cause) implements Structural {
    }

    /** 任意方的字符串类型载荷(引擎不解释 payload;载荷不可变快照)。 */
    record Custom(int tick, long occurredAtMillis, String type, Map<String, Object> payload)
            implements MatchModeInstanceEvent {

        public Custom {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
            payload = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }

        /** 便利工厂:tick=0、millis=当前时刻、空载荷。 */
        public static Custom of(String type) {
            return new Custom(0, System.currentTimeMillis(), type, Map.of());
        }
    }
}
