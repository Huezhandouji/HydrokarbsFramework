package com.hydrokarbs.component.endgate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.ComponentRegistry;
import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.TransitionIntent;
import com.hydrokarbs.testkit.FakeSelfPort;
import com.hydrokarbs.testkit.OfflineAssembly;
import com.hydrokarbs.testkit.TestComponent;

/**
 * 结束门命中调 end()(施工计划 1.5「测试与验收」;16-路线图与验收 §16.1-P0.5):
 * 优先级降序(同 tick 命中取高者,低者短路不求值)/ 未命中不调 end /
 * 命中后重复 update 不重复处置(容器结束请求幂等护栏)/ 结局四枚全走通 /
 * 去向有无两态的结束货币正确构造。零 Bukkit import。
 */
class EndGateComponentTest {

    // ── 假判定器 ────────────────────────────────────────────────

    /** 可控判定器:命中与否、产物、求值次数全可断言。 */
    static final class StubCondition implements EndCondition {

        final int priority;
        final String outcomeReason;
        final boolean withIntent;
        final boolean hit;
        int evaluateCalls;

        StubCondition(int priority, String outcomeReason, boolean withIntent, boolean hit) {
            this.priority = priority;
            this.outcomeReason = outcomeReason;
            this.withIntent = withIntent;
            this.hit = hit;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Optional<GateVerdict> evaluate(com.hydrokarbs.engine.instance.MatchModeInstanceView ctx) {
            evaluateCalls++;
            if (!hit) {
                return Optional.empty();
            }
            return Optional.of(new GateVerdict(
                    new MatchOutcome(MatchOutcome.Kind.WIN, "w-" + priority, outcomeReason),
                    withIntent
                            ? Optional.of(new TransitionIntent(MatchModeSelection.of("target_" + priority), Handoff.empty()))
                            : Optional.empty()));
        }
    }

    /** 带 self 的装配(要检查 endCalls)。 */
    private static final class Rig {
        final FakeSelfPort self = new FakeSelfPort();
        final EndGateComponent gate;

        Rig(EndCondition... conditions) {
            MatchModeInstanceServices services =
                    new MatchModeInstanceServices(self, new ComponentRegistry(), null);
            this.gate = new EndGateComponent.Spec(List.of(conditions)).create("endgate", services);
        }
    }

    // ── 用例 ────────────────────────────────────────────────────

    @Test
    void higherPriorityWinsSameTickAndLowerIsShortCircuited() {
        StubCondition high = new StubCondition(100, "by-100", true, true);
        StubCondition low = new StubCondition(90, "by-90", true, true);
        Rig rig = new Rig(low, high);   // 声明序故意低在前:降序由 priority 决定,不由列表序

        rig.gate.update();
        assertEquals(1, rig.self.endCalls.size(), "命中即调 end");
        assertEquals("by-100", rig.gate.outcome().orElseThrow().reason(), "同 tick 命中取高优先级");
        assertEquals("w-100", rig.gate.outcome().orElseThrow().winnerId());
        assertEquals(1, high.evaluateCalls, "高优先级被求值");
        assertEquals(0, low.evaluateCalls, "findFirst 短路:高者已命中,低者不再求值");
    }

    @Test
    void fallsThroughWhenHighPriorityMisses() {
        StubCondition high = new StubCondition(100, "by-100", true, false);
        StubCondition low = new StubCondition(90, "by-90", true, true);
        Rig rig = new Rig(low, high);

        rig.gate.update();
        assertEquals("by-90", rig.gate.outcome().orElseThrow().reason(), "高优先级未命中时降序落到低者");
        assertEquals(1, high.evaluateCalls);
        assertEquals(1, low.evaluateCalls);
    }

    @Test
    void noHitDoesNotEnd() {
        StubCondition miss = new StubCondition(100, "never", true, false);
        Rig rig = new Rig(miss);

        rig.gate.update();
        rig.gate.update();
        rig.gate.update();
        assertTrue(rig.self.endCalls.isEmpty(), "未命中不调 end");
        assertTrue(rig.gate.outcome().isEmpty(), "未命中无结局可读");
    }

    @Test
    void endResultCarriesIntentOnlyWhenDeclared() {
        StubCondition withIntent = new StubCondition(100, "with", true, true);
        Rig a = new Rig(withIntent);
        a.gate.update();
        assertTrue(a.self.endCalls.get(0).transition().isPresent(), "有去向:结束货币携带意图");
        assertEquals("target_100",
                a.self.endCalls.get(0).transition().orElseThrow().target().modeId());

        StubCondition withoutIntent = new StubCondition(100, "without", false, true);
        Rig b = new Rig(withoutIntent);
        b.gate.update();
        assertTrue(b.self.endCalls.get(0).transition().isEmpty(), "无去向:结束货币不带意图(走模式缺省)");
    }

    @Test
    void allFourOutcomeKindsPassThroughUpdate() {
        for (MatchOutcome.Kind kind : MatchOutcome.Kind.values()) {
            FakeSelfPort self = new FakeSelfPort();
            MatchModeInstanceServices services =
                    new MatchModeInstanceServices(self, new ComponentRegistry(), null);
            EndGateComponent gate = new EndGateComponent.Spec(List.of(
                    new EndCondition() {
                        @Override
                        public int priority() {
                            return 100;
                        }

                        @Override
                        public Optional<GateVerdict> evaluate(
                                com.hydrokarbs.engine.instance.MatchModeInstanceView ctx) {
                            return Optional.of(new GateVerdict(
                                    new MatchOutcome(kind, null, "kind-" + kind), Optional.empty()));
                        }
                    })).create("endgate", services);

            gate.update();
            assertEquals(kind, gate.outcome().orElseThrow().kind(),
                    "结局词汇四枚都必须能穿过求值链");
            assertEquals(1, self.endCalls.size());
        }
        assertEquals(4, MatchOutcome.Kind.values().length, "结局词汇恰四枚");
    }

    /**
     * 命中后重复 update 不重复处置:幂等护栏在容器的结束请求收口
     * (本组件不自建防抖)。真容器直驱(tick 为公开口,不经包私有的装配段)。
     */
    @Test
    void repeatedUpdateDoesNotReDisposeThroughContainerGuard() {
        StubCondition alwaysHits = new StubCondition(100, "always", true, true);
        LinkedHashMap<String, MatchModeComponent.Specification<?>> specs = new LinkedHashMap<>();
        specs.put("endgate", new EndGateComponent.Spec(List.of(alwaysHits)));
        specs.put("test", TestComponent.spec(new ArrayList<>()));

        OfflineAssembly.RecordingSession session = OfflineAssembly.session("s1");
        OfflineAssembly.FakePlatform platform = new OfflineAssembly.FakePlatform();
        MatchModeInstance instance = OfflineAssembly.instance(
                OfflineAssembly.mode("gate_test", specs), session, platform);

        instance.tick();   // 第一拍:命中 → 请求结束(ENDING 置位的外显 = 会话收到一次通知)
        assertEquals(1, session.endNotices.size(), "命中即通知会话一次");

        instance.tick();   // 第二拍:组件照常 update、再次命中调 end —— 护栏挡下
        instance.tick();
        assertEquals(1, session.endNotices.size(), "重复命中不重复处置(requestEnd 幂等)");

        EndGateComponent gate = instance.component(EndGateComponent.class);
        assertEquals("always", gate.outcome().orElseThrow().reason(), "close 前实例仍在、结局可读");
        assertEquals(3, alwaysHits.evaluateCalls, "closed 前判定器照常被求值(隔离只发生在 close)");

        assertDoesNotThrow(instance::close);
    }
}
