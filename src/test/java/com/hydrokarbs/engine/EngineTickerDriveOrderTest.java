package com.hydrokarbs.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionMember;
import com.hydrokarbs.engine.session.SessionRoster;
import com.hydrokarbs.engine.session.SessionService;

/**
 * 节拍器驱动序(施工计划 1.3「测试与验收」;12-代码骨架 §12.2 tickAll):
 * 直调 {@code tickAll()}(包内可见,离线直驱)——
 * 驱动序恒为 待处理切换 → 当前实例 → 内核节律;坏会话被隔离且好会话照常驱动。
 * 离线可跑:零 Bukkit import(Plugin 位置传 null)。
 */
class EngineTickerDriveOrderTest {

    /** 会话测试桩:记录三段驱动序;可注入坏钩子与隔离计数。 */
    static final class ProbeSession extends Session {
        final List<String> steps;
        final boolean failOnInstanceTick;
        int quarantines;

        ProbeSession(String id, List<String> steps, boolean failOnInstanceTick) {
            super(new SessionId(id), new SessionRoster(List.of(new SessionMember(UUID.randomUUID(), true, 1L))));
            this.steps = steps;
            this.failOnInstanceTick = failOnInstanceTick;
        }

        @Override
        public void runPendingSwitch() {
            steps.add(id().value() + ":switch");
        }

        @Override
        public void tickCurrentInstance() {
            steps.add(id().value() + ":instance");
            if (failOnInstanceTick) {
                throw new RuntimeException("session-boom");
            }
        }

        @Override
        public void idleTimers() {
            steps.add(id().value() + ":idle");
        }

        @Override
        public void quarantine() {
            quarantines++;
        }
    }

    /** 假会话服务:返回固定会话集合。 */
    static final class FixedSessionService implements SessionService {
        final List<Session> sessions;

        FixedSessionService(List<Session> sessions) {
            this.sessions = sessions;
        }

        @Override
        public Collection<Session> all() {
            return sessions;
        }
    }

    @Test
    void tickAllDrivesThreeStepsPerSessionAndIsolatesBadSession() {
        List<String> steps = new ArrayList<>();
        ProbeSession bad = new ProbeSession("bad", steps, true);
        ProbeSession good = new ProbeSession("good", steps, false);
        EngineTicker ticker = new EngineTicker(null, new FixedSessionService(List.of(bad, good)));

        ticker.tickAll();   // 直调驱动序方法,不碰 Bukkit

        assertEquals(List.of(
                "bad:switch", "bad:instance",          // 坏会话:实例段抛 → 本会话驱动中止
                "good:switch", "good:instance", "good:idle"), steps,
                "驱动序恒为 switch → instance → idle;坏会话被隔离、好会话照常驱动");
        assertEquals(1, bad.quarantines, "坏会话被隔离(恰一次)");
        assertEquals(0, good.quarantines, "好会话不受坏会话影响");

        ticker.close();   // 句柄为 null:空操作,不抛
    }
}
