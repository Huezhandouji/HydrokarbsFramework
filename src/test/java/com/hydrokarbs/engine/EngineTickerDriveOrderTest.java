package com.hydrokarbs.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SwitchResult;

/**
 * 节拍器驱动序(施工计划 1.3「测试与验收」;12-代码骨架 §12.2 tickAll;
 * ★v4/2.6 计划内修订——随四步扩容增补第四步断言,断言强度不降):
 * 直调 {@code tickAll()}(离线直驱)——驱动序恒为
 * 待处理切换 → 当前实例 → <b>会话组件派发</b> → 内核节律(顺序是规格的一部分,§3.8);
 * 坏会话被隔离且好会话照常驱动。离线可跑:零 Bukkit import(Plugin 位置传 null)。
 */
class EngineTickerDriveOrderTest {

    /** 会话测试桩:记录四段驱动序;可注入坏钩子与隔离计数。 */
    static final class ProbeSession extends Session {
        final List<String> steps;
        final boolean failOnInstanceTick;
        int quarantines;

        ProbeSession(String id, List<String> steps, boolean failOnInstanceTick) {
            super(new SessionSpec(new SessionId(id), "probe-mode", List.of()),
                    SessionPolicy.defaults(SessionPolicy.Topology.ROTATION));
            roster().join(UUID.randomUUID(), 1L);
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
        public void tickSessionComponents() {
            steps.add(id().value() + ":sessionComponents");
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

    /**
     * 假会话服务:返回固定会话集合(2.3 接口扩为八方法后补空桩——
     * 本测试只消费 {@code all()},其余方法零行为,断言面不变)。
     */
    static final class FixedSessionService implements SessionService {
        final List<Session> sessions;

        FixedSessionService(List<Session> sessions) {
            this.sessions = sessions;
        }

        @Override
        public Collection<Session> all() {
            return sessions;
        }

        @Override
        public Session create(SessionSpec spec) {
            throw new UnsupportedOperationException("本测试不消费");
        }

        @Override
        public Optional<Session> sessionOf(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<Session> session(SessionId id) {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<SwitchResult> switchMode(SessionId id, MatchModeSelection target, Handoff payload) {
            throw new UnsupportedOperationException("本测试不消费");
        }

        @Override
        public JoinResult join(SessionId id, UUID player) {
            throw new UnsupportedOperationException("本测试不消费");
        }

        @Override
        public void leave(UUID player, LeaveReason reason) {
            // 本测试不消费
        }

        @Override
        public void dissolve(SessionId id, DissolveReason reason) {
            // 本测试不消费
        }
    }

    @Test
    void tickAllDrivesFourStepsPerSessionAndIsolatesBadSession() {
        List<String> steps = new ArrayList<>();
        ProbeSession bad = new ProbeSession("bad", steps, true);
        ProbeSession good = new ProbeSession("good", steps, false);
        EngineTicker ticker = new EngineTicker(null, new FixedSessionService(List.of(bad, good)));

        ticker.tickAll();   // 直调驱动序方法,不碰 Bukkit

        assertEquals(List.of(
                "bad:switch", "bad:instance",          // 坏会话:实例段抛 → 本会话驱动中止(③④ 不达)
                "good:switch", "good:instance", "good:sessionComponents", "good:idle"), steps,
                "驱动序恒为 switch → instance → sessionComponents → idle(★v4 四步);"
                        + "坏会话被隔离、好会话照常驱动");
        assertEquals(1, bad.quarantines, "坏会话被隔离(恰一次)");
        assertEquals(0, good.quarantines, "好会话不受坏会话影响");

        ticker.close();   // 句柄为 null:空操作,不抛
    }
}
