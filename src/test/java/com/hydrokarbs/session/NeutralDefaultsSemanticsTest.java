package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SessionState;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.testkit.ProbeComponent;

/**
 * ★v1.3(D3/R3)发行配置中立化空值语义(21 号修正案;16 §16.2 零新增编号——
 * 空值语义由既有 ★14 切换原子性断言族覆盖,本类为其空值侧的最小锁定):
 * <ol>
 *   <li>初始模式<b>空</b>或<b>未注册</b> → 启动记 WARN(不再 ERROR)、会话保留
 *       待后补切换(后补切换真实可用);</li>
 *   <li>回退模式<b>空</b> → 回退链跳过一级(fallback 切换)、记 WARN 后直接二级
 *       dissolve 兜底(FALLBACK_DISSOLVE 事件 + 成员安置);</li>
 *   <li>回退模式<b>未注册</b> → 既有「无可用回退目标 → dissolve」分支原样
 *       (A1 拒绝 → 二级解散,SEVERE 兜底日志不变)。</li>
 * </ol>
 * 框架发行配置默认不含任何模式 id(框架不设出厂模式);引擎对空串照走 A1
 * 未注册拒绝,源码零模式字面量(§14.21 纪律不变)。
 */
@Timeout(30)
class NeutralDefaultsSemanticsTest {

    private static MatchModeSelection forceSel(String modeId) {
        return new MatchModeSelection(modeId, java.util.Optional.empty(), true);
    }

    // ── ① 初始模式空/未注册:WARN + 会话保留待后补切换 ──────────────────

    @Test
    void blankInitialModeWarnsAndKeepsSessionForLaterSwitch() throws Exception {
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100));
        Session session = harness.service.create(
                new SessionSpec(new SessionId("s1"), "", List.of(), List.of()));
        harness.ticker.tickAll();
        harness.ticker.tickAll();

        assertEquals(1, harness.service.all().size(), "空初始模式不炸启动:会话保留在册");
        assertEquals(SessionState.RUNNING, session.state(), "会话保留常态(待后补切换)");
        assertTrue(session.currentInstance().isEmpty(), "空名册待机、无实例");
        assertTrue(harness.logLines.stream().anyMatch(line ->
                        line.startsWith("WARNING") && line.contains("初始模式")),
                "空初始模式记 WARN: " + harness.logLines);
        assertTrue(harness.logLines.stream().noneMatch(line ->
                        line.startsWith("SEVERE") && line.contains("初始模式")),
                "初始模式失败不再 SEVERE(v1.3 前为 ERROR,已知问题#5 消除): " + harness.logLines);

        // 后补切换真实可用:模式注册后 switchMode 进入运行(「待后补切换」不是空话)
        var future = harness.service.switchMode(session.id(), MatchModeSelection.of("fb"), Handoff.empty());
        harness.ticker.tickAll();
        harness.ticker.tickAll();
        assertInstanceOf(SwitchResult.Succeeded.class, future.get(2, TimeUnit.SECONDS));
        assertEquals("fb", session.currentInstance().orElseThrow().modeId(), "后补切换后实例就位");
    }

    @Test
    void unregisteredInitialModeWarnsAndKeepsSession() throws Exception {
        SwitchHarness harness = new SwitchHarness("fb", SwitchHarness.mode("fb", 0, 100));
        Session session = harness.service.create(
                new SessionSpec(new SessionId("s1"), "ghost-mode", List.of(), List.of()));
        harness.ticker.tickAll();
        harness.ticker.tickAll();

        assertEquals(1, harness.service.all().size(), "未注册初始模式:会话保留(与空串同语义)");
        assertEquals(SessionState.RUNNING, session.state());
        assertTrue(session.currentInstance().isEmpty());
        assertTrue(harness.logLines.stream().anyMatch(line ->
                        line.startsWith("WARNING") && line.contains("初始模式")),
                "未注册初始模式同样记 WARN: " + harness.logLines);
        assertTrue(harness.logLines.stream().noneMatch(line ->
                        line.startsWith("SEVERE") && line.contains("初始模式")),
                "未注册初始模式不再 SEVERE: " + harness.logLines);
    }

    // ── ② 回退模式空:跳过一级回退,直接二级 dissolve ─────────────────────

    @Test
    void blankFallbackSkipsLevelOneAndDissolvesDirectly() throws Exception {
        List<String> boomLog = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> boomSpecs = new LinkedHashMap<>();
        boomSpecs.put("boom", new ProbeComponent.Spec(boomLog).failOnStart("start-boom"));
        SwitchHarness harness = new SwitchHarness("",
                SwitchHarness.mode("base", 0, 100),
                SwitchHarness.mode("boommode", 0, 100, boomSpecs));
        Session session = harness.createSession("s1", "base");
        List<SessionEvent> events = SwitchHarness.recordEvents(session);
        for (int i = 0; i < 3; i++) {
            harness.service.join(session.id(), UUID.randomUUID());
        }

        var future = harness.service.switchMode(session.id(), forceSel("boommode"), Handoff.empty());
        for (int i = 0; i < 3; i++) {
            harness.ticker.tickAll();
        }

        assertInstanceOf(SwitchResult.Rejected.class, future.get(2, TimeUnit.SECONDS));
        assertTrue(harness.service.all().isEmpty(), "空 fallback:跳过一级回退,会话直接二级解散");
        assertTrue(events.stream().anyMatch(e -> e instanceof SessionEvent.SessionDissolved dissolved
                        && dissolved.reason() == DissolveReason.FALLBACK_DISSOLVE),
                "发解散事件(原因 FALLBACK_DISSOLVE)");
        assertTrue(harness.logLines.stream().anyMatch(line ->
                        line.startsWith("WARNING") && line.contains("回退模式未配置")),
                "跳级记 WARN 日志: " + harness.logLines);
        assertEquals(1, harness.relocations.size(), "成员安置函数被调用(二级兜底)");
    }

    // ── ③ 回退模式未注册:既有「无可用回退目标 → dissolve」分支原样 ───────

    @Test
    void unregisteredFallbackDissolvesViaExistingBranch() throws Exception {
        List<String> boomLog = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> boomSpecs = new LinkedHashMap<>();
        boomSpecs.put("boom", new ProbeComponent.Spec(boomLog).failOnStart("start-boom"));
        SwitchHarness harness = new SwitchHarness("ghost-fb",
                SwitchHarness.mode("base", 0, 100),
                SwitchHarness.mode("boommode", 0, 100, boomSpecs));
        Session session = harness.createSession("s1", "base");
        List<SessionEvent> events = SwitchHarness.recordEvents(session);
        for (int i = 0; i < 3; i++) {
            harness.service.join(session.id(), UUID.randomUUID());
        }

        var future = harness.service.switchMode(session.id(), forceSel("boommode"), Handoff.empty());
        for (int i = 0; i < 3; i++) {
            harness.ticker.tickAll();
        }

        assertInstanceOf(SwitchResult.Rejected.class, future.get(2, TimeUnit.SECONDS));
        assertTrue(harness.service.all().isEmpty(), "未注册 fallback:A1 拒绝 → 二级解散(既有分支)");
        assertTrue(events.stream().anyMatch(e -> e instanceof SessionEvent.SessionDissolved dissolved
                        && dissolved.reason() == DissolveReason.FALLBACK_DISSOLVE),
                "发解散事件(原因 FALLBACK_DISSOLVE)");
        assertTrue(harness.logLines.stream().anyMatch(line ->
                        line.startsWith("SEVERE") && line.contains("回退模式亦装配失败")),
                "二级兜底 SEVERE 日志原样(机制零改动): " + harness.logLines);
    }
}
