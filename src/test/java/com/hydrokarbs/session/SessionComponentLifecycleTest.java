package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.port.PlatformPort;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.testkit.SessionProbeComponent;

/**
 * ★28 会话级生命周期与回收(§16.2;17-D2/D9;02 §2.5.1 派发规则):
 * 声明序 {@code awake}/{@code start};dissolve 时<b>逆序</b> {@code stop};
 * 登记资源(订阅/延时任务)全回收;{@code stop} 幂等(重复解散不重放钩子)。
 * ★v1.7:原 ★32 会话侧标签命题(capabilityTagsVanishAfterDissolve)随能力标签
 * 机制全链退场删除(25 号修正案)。
 */
class SessionComponentLifecycleTest {

    @Test
    void awakeAndStartRunInDeclarationOrder() {
        List<String> log = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));

        // 同类型多件会撞装配 id(同一命名空间去重)——声明序断言用三个不同类型
        harness.createSession("s1", "probe-mode", List.of(
                SessionProbeComponent.spec(log),
                MarkerComponent.spec(log), TailComponent.spec(log)));

        assertEquals(List.of(
                "SessionProbeComponent:awake", "MarkerComponent:awake", "TailComponent:awake",
                "SessionProbeComponent:start", "MarkerComponent:start", "TailComponent:start"),
                log.stream().filter(line -> line.endsWith("awake") || line.endsWith("start")).toList(),
                "声明序 awake 全部完成在 start 之前(装配序 = 声明序 = 派发序)");
    }

    @Test
    void dissolveStopsInReverseOrderAndReclaimsRegisteredResources() {
        List<String> log = new ArrayList<>();
        // later 句柄只记录 close、不执行任务(取消语义;回收断言的可观测面)
        List<Boolean> laterClosed = new ArrayList<>();
        PlatformPort platform = new PlatformPort() {
            @Override
            public Logger logger() {
                return Logger.getLogger("lifecycle-test");
            }

            @Override
            public org.bukkit.plugin.Plugin plugin() {
                return null;
            }

            @Override
            public AutoCloseable scheduleOnce(long delayTicks, Runnable task) {
                return () -> laterClosed.add(true);
            }
        };
        SwitchHarness harness = SwitchHarness.withPlatform(platform, "fb",
                SwitchHarness.mode("fb", 0, 100), SwitchHarness.mode("probe-mode", 0, 100));
        Session session = harness.createSession("s1", "probe-mode", List.of(
                TailComponent.spec(log),                                  // 声明序在前(逆序最后停)
                SessionProbeComponent.spec(log).laterOnStart(5),
                MarkerComponent.subscribing(log)));

        MarkerComponent subscriber = session.sessionComponents().get(MarkerComponent.class);
        int receivedAfterAssembly = subscriber.receivedCount();
        session.events().publish(SessionEvent.MemberJoined.of(session.id(), UUID.randomUUID()));
        assertEquals(receivedAfterAssembly + 1, subscriber.receivedCount(), "订阅面在解散前正常收事件(装配期事件不计)");
        assertTrue(log.stream().noneMatch(line -> line.endsWith(":later-fired")), "解散前 later 任务未执行");
        assertTrue(laterClosed.isEmpty(), "解散前 later 句柄未被回收");

        harness.service.dissolve(session.id(), DissolveReason.ADMIN);

        List<String> stops = log.stream().filter(line -> line.endsWith(":stop")).toList();
        assertEquals(List.of("MarkerComponent:stop", "SessionProbeComponent:stop", "TailComponent:stop"),
                stops, "dissolve 时逆序 stop(声明序的镜像)");
        assertEquals(3, log.stream().filter(line -> line.endsWith(":awake")).count());
        // 登记资源回收:延时任务句柄被统一 close(取消);订阅句柄注销(事件不再送达)
        assertEquals(List.of(Boolean.TRUE), laterClosed, "later 句柄随解散被统一回收(close 调用)");
        assertTrue(log.stream().noneMatch(line -> line.endsWith(":later-fired")), "已回收任务不再执行");
        int receivedBefore = subscriber.receivedCount();
        session.events().publish(SessionEvent.MemberJoined.of(session.id(), UUID.randomUUID()));
        assertEquals(receivedBefore, subscriber.receivedCount(), "解散后订阅句柄已回收,事件不再送达");
    }

    /** 声明序标记件(第二类型:记录钩子,可选 start 期订阅)。 */
    static final class MarkerComponent extends SessionComponent {

        private final List<String> log;
        private final boolean subscribeOnStart;
        private final List<SessionEvent> received = new ArrayList<>();

        private MarkerComponent(String id, com.hydrokarbs.engine.port.SessionServices svc,
                                List<String> log, boolean subscribeOnStart) {
            super(id, svc);
            this.log = log;
            this.subscribeOnStart = subscribeOnStart;
        }

        static Spec spec(List<String> log) {
            return new Spec(log, false);
        }

        static Spec subscribing(List<String> log) {
            return new Spec(log, true);
        }

        @Override
        public void awake() {
            log.add(id() + ":awake");
        }

        @Override
        public void start() {
            log.add(id() + ":start");
            if (subscribeOnStart) {
                svc().self().events().subscribe(SessionEvent.class, received::add);
            }
        }

        @Override
        public void stop() {
            log.add(id() + ":stop");
        }

        int receivedCount() {
            return received.size();
        }

        static final class Spec extends SessionComponent.Specification<MarkerComponent> {

            private final List<String> log;
            private final boolean subscribeOnStart;

            Spec(List<String> log, boolean subscribeOnStart) {
                super("marker");
                this.log = log;
                this.subscribeOnStart = subscribeOnStart;
            }

            @Override
            public MarkerComponent create(String id, com.hydrokarbs.engine.port.SessionServices svc) {
                return new MarkerComponent(id, svc, log, subscribeOnStart);
            }
        }
    }

    /** 声明序尾件(第三类型:纯记录)。 */
    static final class TailComponent extends SessionComponent {

        private final List<String> log;

        private TailComponent(String id, com.hydrokarbs.engine.port.SessionServices svc, List<String> log) {
            super(id, svc);
            this.log = log;
        }

        static Spec spec(List<String> log) {
            return new Spec(log);
        }

        @Override
        public void awake() {
            log.add(id() + ":awake");
        }

        @Override
        public void start() {
            log.add(id() + ":start");
        }

        @Override
        public void stop() {
            log.add(id() + ":stop");
        }

        static final class Spec extends SessionComponent.Specification<TailComponent> {

            private final List<String> log;

            Spec(List<String> log) {
                super("tail");
                this.log = log;
            }

            @Override
            public TailComponent create(String id, com.hydrokarbs.engine.port.SessionServices svc) {
                return new TailComponent(id, svc, log);
            }
        }
    }

    @Test
    void dissolveIsIdempotentForSessionComponentStop() {
        List<String> log = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        Session session = harness.createSession("s1", "probe-mode",
                List.of(SessionProbeComponent.spec(log)));

        harness.service.dissolve(session.id(), DissolveReason.ADMIN);
        harness.service.dissolve(session.id(), DissolveReason.ADMIN);   // 重复解散:幂等

        assertEquals(1, log.stream().filter(line -> line.endsWith(":stop")).count(),
                "stop 只派发一次(重复解散被 DISSOLVING 终态护栏吸收)");
        assertTrue(harness.service.session(session.id()).isEmpty(), "会话已从注册表摘除");
    }
}
