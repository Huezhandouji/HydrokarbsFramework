package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.SessionId;

/**
 * 会话事件总线(2.1 施工「测试与验收」表;§3.1 事件行;★v4/2.6 回归扩八事件):
 * 八事件各自可发布并按序送达;订阅句柄退订后不再收到;单线程连发不丢事件。
 * 载荷断言(并入):八事件载荷字段非空且类型正确(from/to、reason);
 * SessionDissolved 携带 DissolveReason;事件时间戳单调(不减)。
 * 零 Bukkit import。
 */
class SessionEventBusTest {

    private static final SessionId ID = new SessionId("s1");

    private static List<SessionEvent> eightEvents(UUID member) {
        return List.of(
                SessionEvent.SessionCreated.of(ID, "mode-a"),
                SessionEvent.MemberJoined.of(ID, member),
                SessionEvent.MemberLeft.of(ID, member, LeaveReason.QUIT),
                SessionEvent.MemberReconnected.of(ID, member),
                SessionEvent.ModeSwitchStarted.of(ID, "mode-a", "mode-b"),
                SessionEvent.ModeSwitchCompleted.of(ID, "mode-a", "mode-b"),
                SessionEvent.SessionDissolved.of(ID, DissolveReason.ADMIN),
                SessionEvent.SessionComponentQuarantined.of(ID, "probe-component", "update-boom"));
    }

    /** 八事件各自可发布并按序送达订阅者(发布序 = 接收序,同步派发)。 */
    @Test
    void eightEventsPublishAndDeliverInOrder() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> received = new ArrayList<>();
        bus.subscribe(SessionEvent.class, received::add);
        List<SessionEvent> events = eightEvents(UUID.randomUUID());

        events.forEach(bus::publish);

        assertEquals(events, received, "八事件按发布序逐个送达");
        SessionEvent.SessionComponentQuarantined quarantined =
                (SessionEvent.SessionComponentQuarantined) events.get(7);
        assertEquals(ID, quarantined.session(), "隔离事件携带会话 id");
        assertEquals("probe-component", quarantined.componentId(), "隔离事件携带组件 id");
        assertEquals("update-boom", quarantined.reason(), "隔离事件携带原因");
    }

    /** 载荷:八事件字段非空且类型正确;SessionDissolved 携带 DissolveReason;时间戳单调。 */
    @Test
    void payloadsAreTypedNonNullOrMonotonic() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> received = new ArrayList<>();
        bus.subscribe(SessionEvent.class, received::add);
        UUID member = UUID.randomUUID();

        eightEvents(member).forEach(bus::publish);

        assertEquals(8, received.size());
        SessionEvent.SessionCreated created = assertInstanceOf(SessionEvent.SessionCreated.class, received.get(0));
        assertSame(ID, created.session());
        assertNotNull(created.initialModeId(), "SessionCreated 携带 initialModeId");
        assertEquals("mode-a", created.initialModeId());

        SessionEvent.MemberJoined joined = assertInstanceOf(SessionEvent.MemberJoined.class, received.get(1));
        assertEquals(member, joined.member());

        SessionEvent.MemberLeft left = assertInstanceOf(SessionEvent.MemberLeft.class, received.get(2));
        assertEquals(member, left.member());
        assertSame(LeaveReason.QUIT, left.reason(), "MemberLeft 携带 LeaveReason");

        SessionEvent.MemberReconnected reconnected =
                assertInstanceOf(SessionEvent.MemberReconnected.class, received.get(3));
        assertEquals(member, reconnected.member());

        SessionEvent.ModeSwitchStarted started = assertInstanceOf(SessionEvent.ModeSwitchStarted.class, received.get(4));
        assertEquals("mode-a", started.fromMode(), "ModeSwitchStarted 携带 from");
        assertEquals("mode-b", started.toMode(), "ModeSwitchStarted 携带 to");

        SessionEvent.ModeSwitchCompleted completed =
                assertInstanceOf(SessionEvent.ModeSwitchCompleted.class, received.get(5));
        assertEquals("mode-a", completed.fromMode(), "ModeSwitchCompleted 携带 from");
        assertEquals("mode-b", completed.toMode(), "ModeSwitchCompleted 携带 to");

        SessionEvent.SessionDissolved dissolved =
                assertInstanceOf(SessionEvent.SessionDissolved.class, received.get(6));
        assertSame(DissolveReason.ADMIN, dissolved.reason(), "SessionDissolved 携带 DissolveReason");

        for (int i = 1; i < received.size(); i++) {
            assertTrue(received.get(i - 1).occurredAtMillis() <= received.get(i).occurredAtMillis(),
                    "事件时间戳单调不减(第 " + i + " 个)");
        }
    }

    /** 退订后不再收到(订阅句柄显式回收;重复 close 幂等无害)。 */
    @Test
    void closedHandleStopsDelivery() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> received = new ArrayList<>();
        SessionEventBus.Subscription handle = bus.subscribe(SessionEvent.class, received::add);
        UUID member = UUID.randomUUID();
        bus.publish(SessionEvent.MemberJoined.of(ID, member));
        assertEquals(1, received.size(), "前置:退订前正常收到");

        handle.close();
        assertDoesNotThrow(handle::close, "句柄重复 close 幂等无害");

        bus.publish(SessionEvent.MemberJoined.of(ID, member));
        bus.publish(SessionEvent.SessionDissolved.of(ID, DissolveReason.SHUTDOWN));
        assertEquals(1, received.size(), "退订后不再收到任何事件");
    }

    /** 单线程连发不丢事件:一批发布全部送达(内部总线,零 Bukkit)。 */
    @Test
    void burstPublishLosesNoEvents() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> received = new ArrayList<>();
        bus.subscribe(SessionEvent.MemberJoined.class, received::add);

        for (int i = 0; i < 100; i++) {
            bus.publish(SessionEvent.MemberJoined.of(ID, UUID.randomUUID()));
        }

        assertEquals(100, received.size(), "100 连发全部送达,零丢失");
    }

    /** 订阅者异常被隔离:单个订阅者抛异常不中断后续订阅者(总线骨架纪律)。 */
    @Test
    void failingSubscriberIsIsolated() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> received = new ArrayList<>();
        bus.subscribe(SessionEvent.MemberJoined.class, event -> {
            throw new RuntimeException("bad-subscriber");
        });
        bus.subscribe(SessionEvent.MemberJoined.class, received::add);

        bus.publish(SessionEvent.MemberJoined.of(ID, UUID.randomUUID()));

        assertEquals(1, received.size(), "坏订阅者不拖累其他订阅者");
    }

    // ── ★v1.1-D3 镜像(7.5 施工决策 12):加性汇聚点,默认关闭、异常隔离 ──

    /** 镜像先于订阅派发且逐事件同步送达;未挂镜像(默认)行为与既有版本一致。 */
    @Test
    void mirrorReceivesEveryEventBeforeSubscribersAndDefaultsOff() {
        SessionEventBus bare = new SessionEventBus();
        List<SessionEvent> bareReceived = new ArrayList<>();
        bare.subscribe(SessionEvent.class, bareReceived::add);
        bare.publish(SessionEvent.MemberJoined.of(ID, UUID.randomUUID()));
        assertEquals(1, bareReceived.size(), "默认(未挂镜像)派发照旧——加性点关闭即零行为变化");

        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> mirrored = new ArrayList<>();
        List<SessionEvent> subscribed = new ArrayList<>();
        bus.setMirror(mirrored::add);
        bus.subscribe(SessionEvent.class, subscribed::add);
        List<SessionEvent> events = eightEvents(UUID.randomUUID());
        events.forEach(bus::publish);

        assertEquals(events, mirrored, "镜像逐事件按发布序送达(汇聚面看到的事件序 = 发布序)");
        assertEquals(events, subscribed, "本会话订阅派发照旧(镜像不影响原总线语义)");
    }

    /** 镜像异常被隔离:镜像抛 Throwable 不影响本会话订阅派发(7.5 红线),后续镜像恢复后继续。 */
    @Test
    void failingMirrorDoesNotDisturbSessionDispatch() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> received = new ArrayList<>();
        bus.subscribe(SessionEvent.class, received::add);
        bus.setMirror(event -> {
            throw new IllegalStateException("mirror-boom");
        });

        bus.publish(SessionEvent.MemberJoined.of(ID, UUID.randomUUID()));
        bus.publish(SessionEvent.SessionDissolved.of(ID, DissolveReason.ADMIN));

        assertEquals(2, received.size(), "镜像异常不得影响原总线派发(红线)");
        bus.setMirror(event -> { });
        bus.publish(SessionEvent.MemberJoined.of(ID, UUID.randomUUID()));
        assertEquals(3, received.size(), "镜像恢复(重设)后事件流继续");
    }

    /** 镜像可关闭:置 null 后不再镜像(加性点可逆)。 */
    @Test
    void mirrorCanBeDetached() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> mirrored = new ArrayList<>();
        bus.setMirror(mirrored::add);
        bus.publish(SessionEvent.MemberJoined.of(ID, UUID.randomUUID()));
        assertEquals(1, mirrored.size());

        bus.setMirror(null);
        bus.publish(SessionEvent.MemberJoined.of(ID, UUID.randomUUID()));
        assertEquals(1, mirrored.size(), "关闭镜像后不再汇聚");
    }
}
