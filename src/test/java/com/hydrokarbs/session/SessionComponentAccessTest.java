package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.port.MatchModeInstanceAccess;
import com.hydrokarbs.engine.port.SessionView;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.testkit.SessionProbeComponent;
import com.hydrokarbs.testkit.TestComponent;

/**
 * ★29 跨级只读访问(§16.2;17-D5;02 §2.8-决断四):
 * 实例组件经 {@code SessionView#component(s)} 查会话组件;会话组件经
 * {@code currentInstance()} → {@code MatchModeInstanceAccess#component(s)} 查实例组件;
 * 无实例或 {@code ENDING/SWITCHING} 间隙 → {@code empty}(现取不缓存,14.27)。
 */
class SessionComponentAccessTest {

    @Test
    void instanceComponentsSeeSessionComponentsViaSessionView() {
        List<String> log = new ArrayList<>();
        List<String> crossLevel = new ArrayList<>();
        // 实例侧:TestComponent 的 onUpdate 回调里跨级读(session().component)
        TestComponent.Spec reader = TestComponent.spec(log, component -> {
            SessionView view = component.sessionView();
            SessionProbeComponent probe = view.component(SessionProbeComponent.class);
            crossLevel.add(probe != null ? probe.id() : "null");
        });

        LinkedHashMap<String, MatchModeComponent.Specification<?>> instanceSpecs = new LinkedHashMap<>();
        instanceSpecs.put("reader", reader);

        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100, instanceSpecs));
        Session session = harness.createSession("s1", "probe-mode",
                List.of(SessionProbeComponent.spec(new ArrayList<>())));

        harness.ticker.tickAll();   // 实例组件再 update 一拍 → 跨级读继续发生

        SessionProbeComponent viaRegistry = session.sessionComponents().get(SessionProbeComponent.class);
        assertTrue(!crossLevel.isEmpty() && crossLevel.stream().allMatch("SessionProbeComponent"::equals),
                "实例组件每拍经 SessionView#component 都查到会话组件(软接入): " + crossLevel);
        SessionProbeComponent viaView = session.view().component(SessionProbeComponent.class);
        assertSame(viaRegistry, viaView, "视图查询与注册表查询同一实例(每会话一份)");
        assertEquals(session.id(), session.view().id());
    }

    @Test
    void sessionComponentsSeeInstanceComponentsViaAccess() {
        List<String> log = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> instanceSpecs = new LinkedHashMap<>();
        instanceSpecs.put("reader", TestComponent.spec(new ArrayList<>()));

        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100, instanceSpecs));
        // 会话组件在每拍 update 里跨级读当前实例(现取不缓存)
        Session session = harness.createSession("s1", "probe-mode", List.of(
                SessionProbeComponent.spec(log, probe -> {
                    var access = probe.self().currentInstance();
                    access.ifPresentOrElse(a -> seen.add(a.modeId() + ":" + a.component(TestComponent.class).id()),
                            () -> seen.add("empty"));
                })));

        harness.ticker.tickAll();

        assertEquals("probe-mode:reader", seen.get(seen.size() - 1),
                "会话组件经 currentInstance() → MatchModeInstanceAccess#component 查到实例组件");
        assertTrue(seen.stream().noneMatch(entry -> entry.equals("empty")),
                "首切实例就位后每拍都有实例可见(会话组件先于首实例就绪)");
    }

    @Test
    void currentInstanceIsEmptyBeforeFirstInstanceAndInGap() {
        List<String> log = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("probe-mode", 0, 100));
        // 只建会话、直调 ③ 步(不驱动首切实例装配):会话组件已就绪、实例未装配——
        // currentInstance() 必须 empty(首切挂起期属合法间隙)
        Session session = harness.service.create(new SessionSpec(new SessionId("s1"), "probe-mode",
                List.of(), List.of(SessionProbeComponent.spec(log, probe -> {
                    seen.add(probe.self().currentInstance().map(a -> "present").orElse("empty"));
                }))));

        session.tickSessionComponents();   // 直调会话组件派发(③ 步),不触首切实例
        assertEquals(List.of("empty"), seen, "首切实例装配前(切换挂起期)currentInstance() = empty");
        assertEquals("probe-mode", session.spec().initialModeId());

        harness.ticker.tickAll();
        harness.ticker.tickAll();   // 首切实例就位
        assertEquals("present", seen.get(seen.size() - 1), "实例就位后可读");

        // 解散中(DISSOLVING 终态)→ 一律 empty(不回落旧实例,14.27)
        harness.service.dissolve(session.id(), com.hydrokarbs.engine.session.DissolveReason.ADMIN);
        SessionProbeComponent probe = session.sessionComponents().get(SessionProbeComponent.class);
        assertNotNull(probe);
        assertEquals(java.util.Optional.empty(), probe.self().currentInstance(),
                "DISSOLVING 间隙 → empty,不回落旧实例");
    }

    @Test
    void matchModeInstanceServicesStaysThreeMembers() {
        // ★29 表格附带断言:实例侧 svc 保持 3 成员(独立冻结测试另有同款断言,此处为
        // 跨级访问面的就地复核——反射读 record 组件)
        var components = com.hydrokarbs.engine.port.MatchModeInstanceServices.class.getRecordComponents();
        assertNotNull(components);
        assertEquals(List.of("self", "components", "modeInfo"),
                java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).toList(),
                "MatchModeInstanceServices 三成员封闭(★25):跨级扩展走 self() 端口,不加成员");
    }
}
