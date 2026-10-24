package com.hydrokarbs.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 实例事件总线(施工计划 1.3「测试与验收」;11-事件流 §11.1/§11.3):
 * 订阅者异常隔离、{@code is(Class)} 子类型匹配、优先级序、类型可赋值匹配、clear。
 * 离线可跑:零 Bukkit import。
 */
class MatchModeInstanceEventBusIsolationTest {

    private static MatchModeInstanceEvent.ParticipantJoined joined() {
        return new MatchModeInstanceEvent.ParticipantJoined(1, System.currentTimeMillis(), UUID.randomUUID());
    }

    @Test
    void secondSubscriberFailureDoesNotStopOthers() {
        MatchModeInstanceEventBus bus = new MatchModeInstanceEventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(MatchModeInstanceEvent.ParticipantJoined.class, e -> seen.add("first"));
        bus.subscribe(MatchModeInstanceEvent.ParticipantJoined.class, e -> {
            throw new RuntimeException("bad-subscriber");
        });
        bus.subscribe(MatchModeInstanceEvent.ParticipantJoined.class, e -> seen.add("third"));

        bus.publish(joined());   // 不抛:第二个订阅者的异常被隔离

        assertEquals(List.of("first", "third"), seen, "第一、第三个订阅者都收到");
    }

    @Test
    void isMatchesByAssignableType() {
        MatchModeInstanceEvent.Structural joined = joined();
        assertTrue(joined.is(MatchModeInstanceEvent.ParticipantJoined.class), "精确类型匹配");
        assertTrue(joined.is(MatchModeInstanceEvent.Structural.class), "封闭子族匹配");
        assertTrue(joined.is(MatchModeInstanceEvent.class), "根类型匹配");
        assertFalse(joined.is(MatchModeInstanceEvent.ParticipantLeft.class), "兄弟类型不匹配");

        MatchModeInstanceEvent.Custom custom = MatchModeInstanceEvent.Custom.of("any-type");
        assertFalse(custom.is(MatchModeInstanceEvent.Structural.class), "自定义载荷不属于结构子族");
        assertTrue(custom.is(MatchModeInstanceEvent.Custom.class), "自定义载荷匹配自身");
        assertEquals(0, custom.tick(), "便利工厂 tick=0");
        assertEquals(Map.of(), custom.payload(), "便利工厂空载荷");
    }

    @Test
    void priorityDescendingThenSubscriptionOrder() {
        MatchModeInstanceEventBus bus = new MatchModeInstanceEventBus();
        List<String> order = new ArrayList<>();
        bus.subscribe(MatchModeInstanceEvent.ParticipantJoined.class, e -> order.add("low"));   // 订阅序 1,优先级 0
        bus.subscribe(MatchModeInstanceEvent.ParticipantJoined.class, 10, e -> order.add("high")); // 优先级高
        bus.subscribe(MatchModeInstanceEvent.ParticipantJoined.class, e -> order.add("low-2"));  // 订阅序 3,优先级 0

        bus.publish(joined());

        assertEquals(List.of("high", "low", "low-2"), order,
                "优先级降序,同级按订阅序");
    }

    @Test
    void assignableTypeSubscriptionAndClear() {
        MatchModeInstanceEventBus bus = new MatchModeInstanceEventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(MatchModeInstanceEvent.Structural.class, e -> seen.add("structural"));   // 父类型订阅
        bus.subscribe(MatchModeInstanceEvent.Custom.class, e -> seen.add("custom"));           // 不相关类型

        bus.publish(joined());
        assertEquals(List.of("structural"), seen, "类型可赋值匹配:结构子族事件到达父类型订阅者");

        bus.clear();
        bus.publish(joined());
        assertEquals(List.of("structural"), seen, "clear 后不再派发");
    }
}
