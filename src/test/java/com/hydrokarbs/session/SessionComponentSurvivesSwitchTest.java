package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.testkit.SessionProbeComponent;

/**
 * ★33 会话组件不参与切换装配(§16.2;17-D4;02 §3.2-阶段 B 注):
 * 100 次切换后<b>会话组件实例恒等</b>、{@code update} 计数连续(每拍恰 +1,无重置)、
 * 切换 diff 中无会话组件 {@code stop}/{@code awake}(表不参与切换装配——
 * A/B 两阶段与装配代码零改动,切换是「换实例」,不是「换会话」)。
 */
class SessionComponentSurvivesSwitchTest {

    @Test
    void hundredSwitchesKeepSessionComponentIdentityAndCounters() throws Exception {
        List<String> log = new ArrayList<>();
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("mode-a", 0, 100),
                SwitchHarness.mode("mode-b", 0, 100));
        Session session = harness.createSession("s1", "mode-a",
                List.of(SessionProbeComponent.spec(log)));

        SessionProbeComponent probe = session.sessionComponents().get(SessionProbeComponent.class);
        long ticksDriven = 2;   // createSession 内两拍驱动(③ 步每拍恰一次 update)

        for (int i = 0; i < 100; i++) {
            String target = i % 2 == 0 ? "mode-b" : "mode-a";
            var future = harness.service.switchMode(session.id(),
                    new com.hydrokarbs.engine.session.MatchModeSelection(target, Optional.empty(), true),
                    Handoff.empty());
            harness.ticker.tickAll();   // ① 执行 B 段(挂起计划已就绪)
            harness.ticker.tickAll();   // 稳态一拍
            assertEquals(SwitchResult.Succeeded.class, future.get(2, TimeUnit.SECONDS).getClass(),
                    "第 " + (i + 1) + " 次切换成功(豁免绕最短驻留——切换防护不是本测试对象)");
            ticksDriven += 2;
            assertSame(probe, session.sessionComponents().get(SessionProbeComponent.class),
                    "第 " + (i + 1) + " 次切换后查询恒同一实例");
        }

        assertSame(probe, session.sessionComponents().get(SessionProbeComponent.class),
                "100 次切换后会话组件实例恒等");
        assertSame(probe, session.view().component(SessionProbeComponent.class),
                "实例侧视图查询同一实例");
        assertEquals(ticksDriven, probe.updates(),
                "update 计数连续:恰等于驱动拍数(每拍 ③ 步恰一次,切换不重置不补发)");

        assertEquals(1, log.stream().filter(line -> line.endsWith(":awake")).count(),
                "切换 diff 无会话组件 awake(装配只发生在会话创建)");
        assertEquals(1, log.stream().filter(line -> line.endsWith(":start")).count(),
                "切换 diff 无会话组件 start");
        assertEquals(0, log.stream().filter(line -> line.endsWith(":stop")).count(),
                "切换 diff 无会话组件 stop(回收只在 dissolve)");
        assertTrue(harness.service.session(session.id()).isPresent(), "会话全程存活");
    }
}
