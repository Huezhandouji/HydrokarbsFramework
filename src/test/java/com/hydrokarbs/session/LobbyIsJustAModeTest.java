package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionState;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.testkit.ProbeComponent;

/**
 * ★13「大厅只是模式」的框架闸门等价物(2.5 迁玩法线后由 2.3 在框架侧建立;
 * 施工计划 §4):两个假测试模式(任意非禁词 id)经<b>同一</b> {@code switchMode} 入口、
 * 同一状态机、同一事件族切换——「逐字同路径」以归一化轨迹断言:把目标模式 id
 * 归一为占位符后,两条切换轨迹(状态迁移/事件序列/实例钩子序列/结果载荷)逐字相等。
 * 任何给特定模式开的特殊分支都会使两轨迹分叉,测试即红。
 */
@Timeout(30)
class LobbyIsJustAModeTest {

    /** 起始模式(两个用例共用,轨迹内归一为 OTHER)。 */
    private static final String ORIGIN = "field";
    /** 两个待测目标(非禁词 id:机制对任意已注册模式同构)。 */
    private static final String FIRST = "plaza";
    private static final String SECOND = "court";

    /** 归一化:目标 id 替换为 TO、其余替换为 OTHER(轨迹可比的前提)。 */
    private static String norm(String raw, String targetId) {
        if (raw == null || raw.isEmpty()) {
            return "NONE";
        }
        return raw.equals(targetId) ? "TO" : "OTHER";
    }

    /** 一次「从 ORIGIN 切到 target」的完整轨迹(逐字同路径断言的材料)。 */
    private List<String> traceOf(SwitchHarness harness, Session session, String targetId) throws Exception {
        List<String> trace = new ArrayList<>();
        List<SessionEvent> events = SwitchHarness.recordEvents(session);
        for (int i = 0; i < 101; i++) {
            session.idleTimers();                   // 越过最短驻留(两用例同款前置)
        }
        assertEquals(SessionState.RUNNING, session.state(), "前置:起始模式在跑");
        String originInstanceId = session.currentInstance().orElseThrow().id().value();

        var future = harness.service.switchMode(session.id(),
                new MatchModeSelection(targetId, Optional.empty(), true), Handoff.empty());
        trace.add("after-submit:" + session.state());       // 挂起后状态(SWITCHING)
        harness.ticker.tickAll();                           // 执行 B 段
        harness.ticker.tickAll();
        SwitchResult result = future.get(2, TimeUnit.SECONDS);
        trace.add("result:" + result.getClass().getSimpleName());
        assertInstanceOf(SwitchResult.Succeeded.class, result, "切换成功(同一路径)");
        SwitchResult.Succeeded ok = (SwitchResult.Succeeded) result;
        trace.add("from:" + norm(ok.fromMode(), targetId));
        trace.add("to:" + norm(ok.toMode(), targetId));
        trace.add("after-b:" + session.state());            // 完成后状态(RUNNING)
        assertNotEquals(originInstanceId, session.currentInstance().orElseThrow().id().value(),
                "实例已换(新实例)");
        for (SessionEvent event : events) {
            if (event instanceof SessionEvent.ModeSwitchStarted started) {
                trace.add("event:Started(" + norm(started.fromMode(), targetId)
                        + "," + norm(started.toMode(), targetId) + ")");
            } else if (event instanceof SessionEvent.ModeSwitchCompleted completed) {
                trace.add("event:Completed(" + norm(completed.fromMode(), targetId)
                        + "," + norm(completed.toMode(), targetId) + ")");
            } else {
                trace.add("event:" + event.getClass().getSimpleName());
            }
        }
        return trace;
    }

    /** ★13 等价物:两个目标模式的切换轨迹归一化后逐字相等(同一入口/状态机/事件族/钩子序)。 */
    @Test
    void twoModesSwitchThroughIdenticalPathCharacterForCharacter() throws Exception {
        List<String> sharedLog = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> spec = new LinkedHashMap<>();
        spec.put("p", new ProbeComponent.Spec(sharedLog));   // 同款组件声明:钩子序列可比
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode(ORIGIN, 0, 100, spec),
                SwitchHarness.mode(FIRST, 0, 100, spec),
                SwitchHarness.mode(SECOND, 0, 100, spec));

        // 两条独立轨迹:会话各自从 ORIGIN 出发,分别切到两个目标
        Session first = harness.createSession("t1", ORIGIN);
        harness.service.join(first.id(), UUID.randomUUID());
        int firstStart = sharedLog.size();
        List<String> firstTrace = traceOf(harness, first, FIRST);
        List<String> firstHooks = hookSlice(sharedLog, firstStart, sharedLog.size());

        Session second = harness.createSession("t2", ORIGIN);
        harness.service.join(second.id(), UUID.randomUUID());
        int secondStart = sharedLog.size();
        List<String> secondTrace = traceOf(harness, second, SECOND);
        List<String> secondHooks = hookSlice(sharedLog, secondStart, sharedLog.size());

        assertEquals(firstTrace, secondTrace,
                "★13 两个模式的切换轨迹归一化后逐字相等(任何模式特权分支都会使轨迹分叉)");
        assertTrue(firstTrace.contains("after-submit:SWITCHING")
                        && firstTrace.contains("after-b:RUNNING")
                        && firstTrace.contains("from:OTHER") && firstTrace.contains("to:TO"),
                "轨迹覆盖关键位(状态机迁移与载荷方向): " + firstTrace);
        assertEquals(firstHooks, secondHooks, "实例钩子序列同构(同一 B1~B5 管线)");
    }

    /** 取日志切片并剔除轮次差异(实例 id 无关;只比钩子形态序列)。 */
    private static List<String> hookSlice(List<String> log, int from, int to) {
        List<String> slice = new ArrayList<>();
        for (int i = from; i < to; i++) {
            String line = log.get(i);
            if (line.contains(":update") || line.contains(":onEvent")) {
                continue;       // update/onEvent 的次数受 tick 推进影响,非切换路径特征
            }
            slice.add(line.substring(line.indexOf(':') + 1));      // 去组件前缀,只留钩子形态
        }
        return slice;
    }
}
