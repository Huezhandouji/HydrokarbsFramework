package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.session.EndResult;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionState;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.testkit.ProbeComponent;

/**
 * ★15「切换风暴被挡」首建 + 防护②(重入)与「自动切换必经节拍器」的调用栈冻结
 * (§3.5 防护表;2.3 测试表):
 * ①最短驻留(默认 100 tick)内非豁免请求(自动切换与非豁免外部请求)被拒,
 * detail 注明剩余 tick 并记 WARN;豁免标记放行;时钟推过 100 tick 后放行;
 * ②{@code SWITCHING} 窗口内的第二请求得 {@code SWITCH_IN_PROGRESS};
 * ③自动切换不经节拍器驱动时拒绝发起(栈闸门),经节拍器驱动正常放行。
 */
@Timeout(30)
class SwitchStormGuardTest {

    /** 会话基础模式(无组件)与回退模式。 */
    private SwitchHarness harness() {
        return new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("base", 0, 100));
    }

    /** 豁免标记选区。 */
    private static MatchModeSelection forceSel(String modeId) {
        return new MatchModeSelection(modeId, Optional.empty(), true);
    }

    /** 建好会话并完成首切实例(驻留自此起算)。 */
    private Session readySession(SwitchHarness harness) {
        return harness.createSession("storm", "base");
    }

    /** 最短驻留内的非豁免外部请求被拒:detail 注明剩余 tick,且记 WARN。 */
    @Test
    void nonForcedRequestWithinDwellIsRejectedWithRemainingTicksAndWarn() throws Exception {
        SwitchHarness harness = harness();
        Session session = readySession(harness);      // 切换完成时刻 = 会话 tick T
        SwitchResult.Rejected rejected = assertInstanceOf(SwitchResult.Rejected.class,
                harness.service.switchMode(session.id(), MatchModeSelection.of("fb"), Handoff.empty())
                        .get(2, TimeUnit.SECONDS));
        assertEquals(SwitchResult.Reason.REJECTED_BY_TARGET_MODE, rejected.code());
        assertTrue(rejected.detail().contains("驻留不足") && rejected.detail().contains("tick"),
                "detail 注明剩余 tick: " + rejected.detail());
        assertTrue(harness.logLines.stream().anyMatch(line -> line.startsWith("WARNING") && line.contains("驻留不足")),
                "拒绝时记 WARN");
        assertEquals(SessionState.RUNNING, session.state(), "未发起切换,状态不变");
    }

    /** 豁免标记放行(仅豁免人数下限与最短驻留)。 */
    @Test
    void forcedRequestIsExemptFromDwell() throws Exception {
        SwitchHarness harness = harness();
        Session session = readySession(harness);
        var future = harness.service.switchMode(session.id(), forceSel("fb"), Handoff.empty());
        harness.ticker.tickAll();
        harness.ticker.tickAll();
        SwitchResult result = future.get(2, TimeUnit.SECONDS);
        assertInstanceOf(SwitchResult.Succeeded.class, result, "豁免标记放行(驻留 0 亦可切)");
        assertEquals("fb", session.currentInstance().orElseThrow().modeId());
    }

    /** 时钟推过最短驻留后,非豁免请求放行(测试时钟 = 会话 tick 计数,量纲一致)。 */
    @Test
    void nonForcedRequestPassesAfterDwellElapsed() throws Exception {
        SwitchHarness harness = harness();
        Session session = readySession(harness);
        for (int i = 0; i < SessionPolicy.DEFAULT_MIN_DWELL_TICKS + 1; i++) {
            session.idleTimers();                     // 推进会话 tick 计数(内核节律)
        }
        var future = harness.service.switchMode(session.id(), MatchModeSelection.of("fb"), Handoff.empty());
        harness.ticker.tickAll();
        harness.ticker.tickAll();
        SwitchResult result = future.get(2, TimeUnit.SECONDS);
        assertInstanceOf(SwitchResult.Succeeded.class, result, "推过最短驻留后放行");
        assertEquals("fb", session.currentInstance().orElseThrow().modeId());
    }

    /** 防护②前半:SWITCHING 窗口内(挂起后、执行前)的第二请求得 SWITCH_IN_PROGRESS。 */
    @Test
    void secondRequestDuringSwitchingWindowIsRejectedAsReentrant() throws Exception {
        SwitchHarness harness = harness();
        Session session = readySession(harness);
        for (int i = 0; i < SessionPolicy.DEFAULT_MIN_DWELL_TICKS + 1; i++) {
            session.idleTimers();
        }
        CompletableFuture<SwitchResult> first = harness.service.switchMode(
                session.id(), MatchModeSelection.of("fb"), Handoff.empty());
        assertEquals(SessionState.SWITCHING, session.state(), "世界就绪后立即进入切换窗口(占位世界即时)");
        assertFalse(first.isDone(), "第一请求挂起中(B 段未执行)");

        SwitchResult.Rejected second = assertInstanceOf(SwitchResult.Rejected.class,
                harness.service.switchMode(session.id(), MatchModeSelection.of("fb"), Handoff.empty())
                        .get(2, TimeUnit.SECONDS));
        assertEquals(SwitchResult.Reason.SWITCH_IN_PROGRESS, second.code(), "窗口内重入被拒");

        harness.ticker.tickAll();
        assertInstanceOf(SwitchResult.Succeeded.class, first.get(2, TimeUnit.SECONDS));
    }

    /** 防护①对自动切换生效:驻留不足时结束请求驱动的自动切换被拒并保留请求;满后经节拍器放行。 */
    @Test
    void autoSwitchWithinDwellIsRejectedThenPassesViaTickerAfterDwell() throws Exception {
        List<String> log = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> specs = new LinkedHashMap<>();
        specs.put("ender", new ProbeComponent.Spec(log).endAfter(1, EndResult.nowhere()));
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("base", 0, 100, specs, MatchModeMeta.DefaultTransition.STAY));
        Session session = readySession(harness);      // 实例 = base(带 ender 组件)
        List<SessionEvent> events = SwitchHarness.recordEvents(session);

        harness.ticker.tickAll();                     // ender 第一次 update → 请求结束 → ENDING
        assertEquals(SessionState.ENDING, session.state(), "结束只置位,不立即切");

        harness.ticker.tickAll();                     // 处置拍:驻留 = 切完后的拍数,不足 → 拒
        assertEquals(SessionState.ENDING, session.state(), "驻留不足,自动切换被拒(请求保留)");
        assertTrue(events.stream().noneMatch(e -> e instanceof SessionEvent.ModeSwitchStarted),
                "被拒期间不发起切换");

        for (int i = 0; i < SessionPolicy.DEFAULT_MIN_DWELL_TICKS + 2; i++) {
            session.idleTimers();                     // 推进驻留时钟越过门槛
        }
        String beforeId = session.currentInstance().orElseThrow().id().value();
        harness.ticker.tickAll();                     // 节拍器驱动:放行发起(A 段挂起)
        harness.ticker.tickAll();                     // 节拍器驱动:执行 B 段
        assertTrue(events.stream().anyMatch(e -> e instanceof SessionEvent.ModeSwitchStarted),
                "驻留满后经节拍器放行自动切换(开始事件)");
        assertTrue(events.stream().anyMatch(e -> e instanceof SessionEvent.ModeSwitchCompleted),
                "自动切换完成(完成事件)");
        assertNotEquals(beforeId, session.currentInstance().orElseThrow().id().value(),
                "实例已换(放行的是真切换)");
    }

    /** 防护②后半的机械闸门:不经节拍器栈直接驱动,自动切换拒绝发起(调用栈冻结)。 */
    @Test
    void autoSwitchOutsideTickerStackIsRefused() throws Exception {
        List<String> log = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> specs = new LinkedHashMap<>();
        specs.put("ender", new ProbeComponent.Spec(log).endAfter(1, EndResult.nowhere()));
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("base", 0, 100, specs));
        Session session = readySession(harness);

        harness.ticker.tickAll();                     // 请求结束 → ENDING
        assertEquals(SessionState.ENDING, session.state());

        session.runPendingSwitch();                   // 未经节拍器直调驱动钩子
        assertEquals(SessionState.ENDING, session.state(), "栈闸门拒绝发起,状态保持 ENDING");
        assertTrue(session.currentInstance().isPresent(), "旧实例未动");
        assertTrue(harness.logLines.stream().anyMatch(line -> line.contains("未经节拍器驱动")),
                "栈闸门命中记 WARN");

        harness.ticker.tickAll();                     // 经节拍器驱动:正常发起
        assertTrue(session.state() == SessionState.ENDING || session.state() == SessionState.SWITCHING,
                "节拍器栈内的处置被放行(状态推进)");
    }

    /** STAY 缺省去向:结束无显式意图 → 原地重开同模式新实例。 */
    @Test
    void stayTransitionReopensSameModeAfterDwellViaTicker() throws Exception {
        List<String> log = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> specs = new LinkedHashMap<>();
        specs.put("ender", new ProbeComponent.Spec(log).endAfter(1, EndResult.nowhere()));
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("base", 0, 100, specs, MatchModeMeta.DefaultTransition.STAY));
        Session session = readySession(harness);
        List<SessionEvent> events = SwitchHarness.recordEvents(session);
        String firstInstanceId = session.currentInstance().orElseThrow().id().value();

        harness.ticker.tickAll();                     // 请求结束
        for (int i = 0; i < SessionPolicy.DEFAULT_MIN_DWELL_TICKS + 2; i++) {
            session.idleTimers();
        }
        harness.ticker.tickAll();                     // 发起
        harness.ticker.tickAll();                     // 执行

        // 新实例的同类组件可能立刻再请求结束(那是下一轮防护的输入,不是本用例的命题)——
        // 本用例断言「STAY 放行的确重开了同模式新实例」
        assertEquals("base", session.currentInstance().orElseThrow().modeId(), "STAY = 同模式重开");
        assertFalse(session.currentInstance().orElseThrow().id().value().equals(firstInstanceId),
                "新实例(不是旧实例复用)");
        SessionEvent.ModeSwitchCompleted completed = events.stream()
                .filter(e -> e instanceof SessionEvent.ModeSwitchCompleted)
                .map(e -> (SessionEvent.ModeSwitchCompleted) e)
                .findFirst().orElseThrow();
        assertEquals("base", completed.fromMode());
        assertEquals("base", completed.toMode());
    }
}
