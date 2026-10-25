package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.ComponentDependencyException;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SessionState;
import com.hydrokarbs.testkit.SessionProbeComponent;

/**
 * ★30 会话组件故障隔离不解散(§16.2;17-D9;02 §2.5.1):
 * 运行期 {@code update}/{@code onEvent} 抛 → 该组件停用(此后跳过其派发)+
 * {@code SessionComponentQuarantined}(会话 id/组件 id/原因)+ <b>会话存活</b>、
 * 其余组件照常;<b>装配期</b> {@code awake}/{@code start} 抛 → 会话创建失败
 * (响亮上抛、不入存活表)——两种失败语义不可混淆。
 */
class SessionComponentQuarantineTest {

    @Test
    void updateThrowQuarantinesComponentButSessionSurvives() {
        List<String> badLog = new ArrayList<>();
        List<String> goodLog = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        // 自行装配并先订阅再驱动:隔离事件必须落在订阅窗口内(create 便捷口的两拍
        // 驱动会把隔离提前到订阅之前)
        Session session = harness.service.create(new com.hydrokarbs.engine.session.SessionSpec(
                new com.hydrokarbs.engine.session.SessionId("s1"), "probe-mode", List.of(),
                List.of(BadComponent.spec(badLog, "update-boom"),
                        SessionProbeComponent.spec(goodLog))));
        List<SessionEvent> events = SwitchHarness.recordEvents(session);

        harness.ticker.tickAll();   // ③ 步:坏件 update 抛 → 隔离;好件照常
        harness.ticker.tickAll();   // 第二拍:坏件不再派发;好件继续

        SessionComponentQuarantinedEvents expected = eventsOf(events);
        assertEquals(1, expected.count, "恰发一次 SessionComponentQuarantined");
        assertEquals(session.id(), expected.session, "事件携带会话 id");
        assertEquals("BadComponent", expected.componentId, "事件携带组件 id");
        assertTrue(expected.reason.contains("update-boom"), "事件携带原因(异常消息): " + expected.reason);

        assertEquals(1, badLog.stream().filter(line -> line.endsWith(":update")).count(),
                "坏件 update 只派发一次(隔离后跳过其派发)");
        assertEquals(2, goodLog.stream().filter(line -> line.endsWith(":update")).count(),
                "好件两拍照常 update(其余组件不受影响)");
        assertTrue(session.sessionComponents().isQuarantined(
                        session.sessionComponents().byId("BadComponent").orElseThrow()),
                "坏件已打标隔离");
        assertTrue(harness.service.session(session.id()).isPresent()
                        && session.state() == SessionState.RUNNING,
                "会话不解散,照常存活");
    }

    @Test
    void onEventThrowQuarantinesComponent() {
        List<String> badLog = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        // onEvent 注入限定事件类型:装配期 SessionCreated 与首切 ModeSwitch* 广播不抛
        //(onEvent 面在组件就绪后即活跃,D9 按「首个命中事件」隔离)
        Session session = harness.createSession("s1", "probe-mode", List.of(
                BadComponent.spec(badLog, null)
                        .failOnEvent(SessionEvent.MemberJoined.class, "onEvent-boom")));
        List<SessionEvent> events = SwitchHarness.recordEvents(session);

        // 会话事件触发 onEvent 派发:坏件抛 → 隔离(会话不解散)
        session.events().publish(SessionEvent.MemberJoined.of(session.id(), UUID.randomUUID()));

        assertEquals(1, events.stream()
                        .filter(e -> e instanceof SessionEvent.SessionComponentQuarantined).count(),
                "onEvent 抛同样触发隔离事件");
        assertEquals(1, badLog.stream().filter(line -> line.endsWith(":onEvent:MemberJoined")).count(),
                "恰在命中的事件上抛一次(装配期广播不误伤)");
        assertEquals(session.id(), harness.service.session(session.id()).orElseThrow().id(),
                "会话不解散");
    }

    /** 装配期 awake 抛 → 会话创建失败(响亮上抛、不入存活表、无组件带着起跑)。 */
    @Test
    void assemblyAwakeThrowFailsSessionCreationLoudly() {
        List<String> log = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));

        assertThrows(RuntimeException.class, () -> harness.service.create(new SessionSpec(
                new SessionId("s1"), "probe-mode", List.of(),
                List.of(SessionProbeComponent.spec(log).failOnAwake("awake-boom")))),
                "装配期 awake 抛 → 会话创建失败(响亮)");
        assertTrue(harness.service.session(new SessionId("s1")).isEmpty(), "失败会话不入存活表");
        assertTrue(log.stream().noneMatch(line -> line.endsWith(":start")),
                "无组件带着起跑(装配中止于 awake)");
    }

    /** 装配期 start 抛 → 会话创建失败(与运行期隔离语义区分的另一半)。 */
    @Test
    void assemblyStartThrowFailsSessionCreationLoudly() {
        List<String> log = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));

        assertThrows(RuntimeException.class, () -> harness.service.create(new SessionSpec(
                new SessionId("s2"), "probe-mode", List.of(),
                List.of(SessionProbeComponent.spec(log).failOnStart("start-boom")))),
                "装配期 start 抛 → 会话创建失败(响亮)");
        assertTrue(harness.service.session(new SessionId("s2")).isEmpty());
        assertTrue(log.stream().noneMatch(line -> line.endsWith(":update")),
                "未生效的组件零运行期派发");
    }

    /** 装配期依赖缺失 → ComponentDependencyException,会话创建失败。 */
    @Test
    void missingRequiredDependencyFailsSessionCreation() {
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        SessionComponent.Specification<?> lonely = new SessionProbeComponent.Spec(new ArrayList<>())
                .requires(BadComponent.class);   // 声明的必需类型不在表内 → 缺依赖(§2.6)

        assertThrows(ComponentDependencyException.class, () -> harness.service.create(new SessionSpec(
                        new SessionId("s3"), "probe-mode", List.of(), List.of(lonely))),
                "缺会话级必需依赖 → ComponentDependencyException(会话创建失败)");
        assertTrue(harness.service.session(new SessionId("s3")).isEmpty());
    }

    // ── 坏件(独立类型:装配 id 推导自类型名,与探针区分) ─────────────

    /** 可注入失败点的坏件:update/onEvent 按配置抛。 */
    static final class BadComponent extends SessionComponent {

        private final Spec spec;

        private BadComponent(String id, com.hydrokarbs.engine.port.SessionServices svc, Spec spec) {
            super(id, svc);
            this.spec = spec;
        }

        static Spec spec(List<String> log, String updateMessage) {
            return new Spec(log, updateMessage, null, null);
        }

        @Override
        public void update() {
            spec.log.add(id() + ":update");
            if (spec.updateMessage != null) {
                throw new IllegalStateException(spec.updateMessage);
            }
        }

        @Override
        public void onEvent(SessionEvent event) {
            spec.log.add(id() + ":onEvent:" + event.getClass().getSimpleName());
            boolean hit = spec.onEventMessage != null
                    && (spec.onEventType == null || spec.onEventType.isInstance(event));
            if (hit) {
                throw new IllegalStateException(spec.onEventMessage);
            }
        }

        static final class Spec extends SessionComponent.Specification<BadComponent> {

            private final List<String> log;
            private final String updateMessage;
            private final String onEventMessage;
            private final Class<? extends SessionEvent> onEventType;

            Spec(List<String> log, String updateMessage, String onEventMessage,
                 Class<? extends SessionEvent> onEventType) {
                super("bad");
                this.log = log;
                this.updateMessage = updateMessage;
                this.onEventMessage = onEventMessage;
                this.onEventType = onEventType;
            }

            Spec failOnEvent(String message) {
                return new Spec(log, updateMessage, message, null);
            }

            /** 限定事件类型的 onEvent 注入(装配期广播不误伤)。 */
            Spec failOnEvent(Class<? extends SessionEvent> type, String message) {
                return new Spec(log, updateMessage, message, type);
            }

            @Override
            public BadComponent create(String id, com.hydrokarbs.engine.port.SessionServices svc) {
                return new BadComponent(id, svc, this);
            }
        }
    }

    // ── 断言辅助 ─────────────────────────────────────────────────

    private record SessionComponentQuarantinedEvents(int count, SessionId session, String componentId,
                                                     String reason) {
    }

    private static SessionComponentQuarantinedEvents eventsOf(List<SessionEvent> events) {
        int count = 0;
        SessionId session = null;
        String componentId = null;
        String reason = null;
        for (SessionEvent event : events) {
            if (event instanceof SessionEvent.SessionComponentQuarantined quarantined) {
                count++;
                session = quarantined.session();
                componentId = quarantined.componentId();
                reason = quarantined.reason();
            }
        }
        assertNotNull(reason);
        return new SessionComponentQuarantinedEvents(count, session, componentId, reason);
    }
}
