package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.testkit.SessionProbeComponent;

/**
 * ★27 会话级组件每会话一份(§16.2;17-v4变更指示书 D3;02 §2.5.1):
 * 同一 Spec(共享 SPEC 常量)装配两个会话 → <b>两个不同实例</b>;同会话内一切查询
 * (组件互查/内核查询/按 id)恒返回<b>同一实例</b>;跨会话互不可见;注册表挂在
 * Session 上、<b>非进程级静态</b>(arena 拓扑 N 会话并发跑同一模板不串台的机械保证)。
 */
class SessionComponentSingletonTest {

    /** 共享描述符常量(跨会话共享的只能是不可变声明——同一 Spec 实例)。 */
    private static final SessionProbeComponent.Spec SHARED_SPEC =
            SessionProbeComponent.spec(new ArrayList<>());

    private static SessionSpec specWith(String id, SessionProbeComponent.Spec componentSpec) {
        return new SessionSpec(new SessionId(id), "probe-mode", List.of(), List.of(componentSpec));
    }

    @Test
    void sameSpecYieldsDistinctInstancesAcrossSessions() {
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        Session first = harness.createSession("s1", "probe-mode", List.of(SHARED_SPEC));
        Session second = harness.createSession("s2", "probe-mode", List.of(SHARED_SPEC));

        SessionProbeComponent inFirst = first.sessionComponents().get(SessionProbeComponent.class);
        SessionProbeComponent inSecond = second.sessionComponents().get(SessionProbeComponent.class);

        assertNotNull(inFirst);
        assertNotNull(inSecond);
        assertNotSame(inFirst, inSecond, "同 Spec 装配两个会话 → 两个不同实例(进程级单例会串台,已修正为每会话一份)");
        assertEquals("SessionProbeComponent", inFirst.id());
        assertEquals("SessionProbeComponent", inSecond.id());
    }

    @Test
    void queriesWithinSessionAlwaysReturnSameInstance() {
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        Session session = harness.createSession("s1", "probe-mode", List.of(SHARED_SPEC));

        SessionProbeComponent byType = session.sessionComponents().get(SessionProbeComponent.class);
        SessionProbeComponent byTypeAgain = session.sessionComponents().get(SessionProbeComponent.class);
        SessionProbeComponent byId = (SessionProbeComponent) session.sessionComponents()
                .byId("SessionProbeComponent").orElseThrow();
        SessionProbeComponent bySiblingsView = session.view().component(SessionProbeComponent.class);

        assertSame(byType, byTypeAgain, "同会话按类型查询恒同一实例");
        assertSame(byType, byId, "同会话按 id 查询恒同一实例");
        assertSame(byType, bySiblingsView, "实例侧只读会话视图查询恒同一实例");
        assertEquals(1, session.view().components(SessionProbeComponent.class).size(),
                "同会话同类型查询不复制实例");
    }

    @Test
    void sessionsDoNotSeeEachOthersComponents() {
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        Session first = harness.createSession("s1", "probe-mode",
                List.of(SessionProbeComponent.tagged(new ArrayList<>(), "first-only-tag")));
        Session second = harness.createSession("s2", "probe-mode");

        SessionProbeComponent inFirst = first.sessionComponents().get(SessionProbeComponent.class);
        assertNotNull(inFirst);
        assertTrue(second.sessionComponents().byId("SessionProbeComponent").isEmpty(),
                "会话二查不到会话一的组件(id 命名空间隔离)");
        assertTrue(second.view().components(SessionProbeComponent.class).isEmpty(),
                "会话二只读视图查不到会话一的组件");
        assertTrue(first.sessionComponentCapabilities().contains("first-only-tag"),
                "会话一持有本会话标签");
        assertFalse(second.sessionComponentCapabilities().contains("first-only-tag"),
                "会话一的标签不泄入会话二(A2 并集按会话隔离)");
    }

    @Test
    void registryIsPerSessionNotProcessStatic() {
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        Session first = harness.createSession("s1", "probe-mode", List.of(SHARED_SPEC));
        Session second = harness.createSession("s2", "probe-mode");

        assertNotSame(first.sessionComponents(), second.sessionComponents(),
                "注册表挂在 Session 上,每会话一份(不是进程级静态)");
        assertEquals(List.of("SessionProbeComponent"), first.sessionComponents().boundIds());
        assertTrue(second.sessionComponents().boundIds().isEmpty(), "会话二的注册表不串会话一的登记");
    }
}
