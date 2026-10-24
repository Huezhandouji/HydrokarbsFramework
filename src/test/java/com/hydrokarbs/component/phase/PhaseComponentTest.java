package com.hydrokarbs.component.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.ComponentRegistry;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.testkit.FakeSelfPort;

/**
 * 阶段表驱动假流程(施工计划 1.5「测试与验收」;16-路线图与验收 §16.1-P0.5):
 * 等待型满足进入 / 计时型到点推进 / jump 未知抛 / 末阶段停留与循环 /
 * 转场事件三字段 / 倒计时数值。阶段表全为纯数据,零 Bukkit import。
 */
class PhaseComponentTest {

    // ── 装配工具 ────────────────────────────────────────────────

    /** 每用例独立的假环境:自面(tick 手动推进)+ 事件收集。 */
    private static final class Rig {
        final FakeSelfPort self = new FakeSelfPort();
        final List<PhaseChanged> events = new ArrayList<>();

        Rig(List<PhaseSpec> phases) {
            this(phases, false);
        }

        Rig(List<PhaseSpec> phases, boolean loopOnFinish) {
            self.bus.subscribe(PhaseChanged.class, events::add);
            MatchModeInstanceServices services =
                    new MatchModeInstanceServices(self, new ComponentRegistry(), null);
            component = new PhaseComponent.Spec(phases, loopOnFinish).create("phases", services);
        }

        final PhaseComponent component;

        /** 推进一拍(镜像容器节拍:先推进时钟,再驱动组件)。 */
        void tick() {
            self.advanceTick();
            component.update();
        }
    }

    private static PhaseSpec await(String id, AtomicBoolean flag) {
        return new PhaseSpec(id, PhaseSpec.Kind.AWAIT, view -> flag.get(), null);
    }

    private static PhaseSpec timed(String id, int durationTicks) {
        return new PhaseSpec(id, PhaseSpec.Kind.TIMED, null, durationTicks);
    }

    // ── 用例 ────────────────────────────────────────────────────

    @Test
    void awaitAdvancesWhenPredicateTurnsTrue() {
        AtomicBoolean ready = new AtomicBoolean(false);
        Rig rig = new Rig(List.of(await("P1", ready), timed("P2", 5)));

        rig.self.advanceTick();     // tick=1
        rig.component.start();
        assertEquals("P1", rig.component.currentId(), "先停在等待型首阶段");
        assertEquals(1, rig.events.size(), "首阶段进入也发转场事件");
        PhaseChanged first = rig.events.get(0);
        assertNull(first.fromPhaseId(), "首阶段无前驱");
        assertEquals("P1", first.toPhaseId());
        assertEquals(0L, first.elapsedTicks());
        assertEquals(1, first.tick(), "事件携带实例 tick");

        rig.tick();                 // 谓词未满足
        assertEquals("P1", rig.component.currentId());
        assertEquals(1, rig.events.size(), "未满足不转场");

        ready.set(true);
        rig.tick();                 // tick=3:谓词为真即转
        assertEquals("P2", rig.component.currentId());
        assertEquals(2, rig.events.size());
        PhaseChanged transition = rig.events.get(1);
        assertEquals("P1", transition.fromPhaseId());
        assertEquals("P2", transition.toPhaseId());
        assertEquals(2L, transition.elapsedTicks(), "在 P1 停留 2 拍(tick1 进入、tick3 离开)");
        assertEquals(3, transition.tick());
        assertTrue(transition.occurredAtMillis() > 0, "带时间组件");
    }

    @Test
    void timedAdvancesWhenDurationElapses() {
        Rig rig = new Rig(List.of(timed("T1", 3), timed("T2", 5)));

        rig.self.advanceTick();     // tick=1
        rig.component.start();
        assertEquals("T1", rig.component.currentId());
        assertEquals(3, rig.component.countdown().orElseThrow().totalTicks());
        assertEquals(3, rig.component.countdown().orElseThrow().remainingTicks(), "进入当拍剩余 = 全额");

        rig.tick();                 // tick=2,elapsed=1
        assertFalse(rig.component.currentExpired());
        assertEquals(2, rig.component.countdown().orElseThrow().remainingTicks());
        rig.tick();                 // tick=3,elapsed=2
        assertEquals(1, rig.component.countdown().orElseThrow().remainingTicks());
        assertEquals(1, rig.events.size(), "未到点不转场");

        rig.tick();                 // tick=4,elapsed=3 → 到点转场
        assertEquals("T2", rig.component.currentId());
        assertEquals(2, rig.events.size());
        assertEquals(3L, rig.events.get(1).elapsedTicks(), "在 T1 停留 3 拍");
        assertEquals(5, rig.component.countdown().orElseThrow().remainingTicks(), "新阶段进入当拍剩余 = 全额");
        assertEquals(5, rig.component.countdown().orElseThrow().totalTicks());
    }

    @Test
    void awaitCountdownIsEmpty() {
        AtomicBoolean ready = new AtomicBoolean(false);
        Rig rig = new Rig(List.of(await("P1", ready), timed("T2", 5)));
        rig.self.advanceTick();
        rig.component.start();
        assertTrue(rig.component.countdown().isEmpty(), "等待型无倒计时");
    }

    @Test
    void jumpUnknownIdThrows() {
        Rig rig = new Rig(List.of(timed("T1", 3), timed("T2", 5)));
        rig.self.advanceTick();
        rig.component.start();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> rig.component.jump("nope"), "未知阶段 id 响亮失败");
        assertTrue(e.getMessage().contains("nope"), "异常消息点名未知 id");
        assertEquals("T1", rig.component.currentId(), "失败的 jump 不改状态");
    }

    @Test
    void jumpGoesToDeclaredPhase() {
        Rig rig = new Rig(List.of(timed("T1", 100), timed("T2", 100)));
        rig.self.advanceTick();
        rig.component.start();
        rig.self.advanceTick();     // tick=2
        rig.component.jump("T2");
        assertEquals("T2", rig.component.currentId());
        assertEquals(2, rig.events.size());
        assertEquals("T1", rig.events.get(1).fromPhaseId());
        assertEquals("T2", rig.events.get(1).toPhaseId());
        assertEquals(1L, rig.events.get(1).elapsedTicks());
    }

    @Test
    void lastPhaseStaysWithoutLoop() {
        Rig rig = new Rig(List.of(timed("T1", 1), timed("T2", 1)));
        rig.self.advanceTick();
        rig.component.start();
        rig.tick();                 // T1 到点 → T2
        assertEquals("T2", rig.component.currentId());
        rig.tick();                 // T2 到点但末阶段停留
        rig.tick();
        rig.tick();
        assertEquals("T2", rig.component.currentId(), "末阶段停留(缺省不循环)");
        assertEquals(2, rig.events.size(), "停留后不再发转场事件");
        assertTrue(rig.component.currentExpired(), "末阶段持续处于已走完状态");
        assertEquals(0, rig.component.countdown().orElseThrow().remainingTicks());
    }

    @Test
    void lastPhaseLoopsWhenDeclared() {
        Rig rig = new Rig(List.of(timed("T1", 1), timed("T2", 1)), true);
        rig.self.advanceTick();
        rig.component.start();
        rig.tick();                 // → T2
        rig.tick();                 // T2 走完 → 循环回首阶段
        assertEquals("T1", rig.component.currentId(), "loopOnFinish=true 循环回首阶段");
        assertEquals(3, rig.events.size());
        assertEquals("T2", rig.events.get(2).fromPhaseId());
        assertEquals("T1", rig.events.get(2).toPhaseId());
        rig.tick();                 // T1 又走完 → 再进 T2
        assertEquals("T2", rig.component.currentId());
        assertEquals(4, rig.events.size());
    }

    @Test
    void updateBeforeStartIsNoOp() {
        Rig rig = new Rig(List.of(timed("T1", 1)));
        rig.self.advanceTick();
        rig.component.update();     // 未 start:防御性空转
        assertNull(rig.component.currentId(), "start 前无当前阶段");
        assertTrue(rig.events.isEmpty());
        assertFalse(rig.component.currentExpired());
        assertTrue(rig.component.countdown().isEmpty());
    }
}
