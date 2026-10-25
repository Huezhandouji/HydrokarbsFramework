package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.session.ConnectionTracker;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SessionState;

/**
 * 会话策略与内核节律(2.1 施工「测试与验收」表;§3.5/§3.6/§15.2;★v4-D11 回改后):
 * 空场判定已下放 {@code component/session/EmptyArenaComponent}(★31 断言随迁
 * EmptyArenaComponentTest,按 tick 量纲重写);本测试保留——策略字段校验(负值响亮拒)、
 * minDwellTicks 默认 100、宽限默认 60s、宽限清点接线、会话 tick 计数、状态机迁移矩阵。
 * 零 Bukkit import、手动时钟。
 */
class SessionPolicyTest {

    private static final long T0 = 1_000_000L;

    private static SessionSpec spec(String id) {
        return new SessionSpec(new SessionId(id), "policy-probe", List.of("map_a"));
    }

    private static Session session() {
        return new Session(spec("arena-1"), SessionPolicy.defaults(SessionPolicy.Topology.ARENA));
    }

    /** 空场字段已删(D11):三参 record;负值仍响亮拒(驻留/宽限)。 */
    @Test
    void policyRejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () ->
                new SessionPolicy(SessionPolicy.Topology.ARENA, -1, 60));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionPolicy(SessionPolicy.Topology.ARENA, 100, -1));
        assertThrows(NullPointerException.class, () ->
                new SessionPolicy(null, 100, 60));
    }

    /** §15.2 基线:minDwellTicks 默认 100、宽限默认 60s(空场行已随 D11 迁出本测试)。 */
    @Test
    void policyDefaultsMatchConfigBaseline() {
        SessionPolicy policy = SessionPolicy.defaults(SessionPolicy.Topology.ARENA);
        assertEquals(SessionPolicy.DEFAULT_MIN_DWELL_TICKS, policy.minDwellTicks());
        assertEquals(100, policy.minDwellTicks(), "min-dwell-ticks 默认 100(§3.5 防护①,2.3 消费)");
        assertEquals(SessionPolicy.DEFAULT_DISCONNECT_GRACE_SECONDS, policy.disconnectGraceSeconds());
        assertEquals(60, policy.disconnectGraceSeconds(), "disconnect-grace-seconds 默认 60(宽限断言不动)");
        assertEquals(60_000L, new ConnectionTracker().graceMillis(), "宽限默认与 ConnectionTracker 同源");
        assertEquals(60_000L, policy.disconnectGraceMillis());
    }

    /** 宽限清点接线:断线成员宽限期内保留在册,期满经 idleTimers 移除;重连销账免于清场。 */
    @Test
    void graceSweepRemovesExpiredMembersViaIdleTimers() {
        Session session = session();
        UUID quitter = UUID.randomUUID();
        UUID returner = UUID.randomUUID();
        session.roster().join(quitter, T0);
        session.roster().join(returner, T0);

        session.roster().markDisconnected(quitter, T0);
        session.tracker().onDisconnect(quitter, T0);
        session.idleTimers(T0 + 59_999L);
        assertTrue(session.roster().contains(quitter), "宽限期内成员保留在册(名册侧不移除)");

        session.idleTimers(T0 + 60_000L);
        assertFalse(session.roster().contains(quitter), "宽限期满由内核节律清点移除");
        assertTrue(session.roster().contains(returner), "在线成员不受清点影响");

        long later = T0 + 200_000L;
        session.roster().markDisconnected(returner, later);
        session.tracker().onDisconnect(returner, later);
        session.tracker().onReconnect(returner);            // 宽限期内重连:销账
        session.roster().markReconnected(returner);
        session.idleTimers(later + 120_000L);
        assertTrue(session.roster().contains(returner), "重连销账后,远超宽限的推进也不移除");
    }

    /** 会话 tick 计数(接线契约④):每 tick 经 idleTimers 递增——min-dwell 驻留基准。 */
    @Test
    void sessionTicksAdvanceOncePerIdleTimersCall() {
        Session session = session();

        assertEquals(0, session.ticks(), "构造时零计数");
        session.idleTimers(T0);
        session.idleTimers(T0 + 50L);
        session.idleTimers(T0 + 100L);
        assertEquals(3, session.ticks(), "每拍恰 +1(手动时钟推进三拍)");
    }

    /** 状态机:初始 RUNNING;in() 布尔查询;迁移矩阵冻结(非法迁移的机械面,写点守卫照此拒绝)。 */
    @Test
    void sessionStateMatrixIsFrozen() {
        Session session = session();

        assertEquals(SessionState.RUNNING, session.state(), "构造即 RUNNING");
        assertTrue(session.in(SessionState.RUNNING, SessionState.SWITCHING), "in 任一命中即真");
        assertFalse(session.in(SessionState.ENDING, SessionState.DISSOLVING), "不含当前态即假");

        // 合法迁移(§3.1/§3.2 矩阵逐格)
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.ENDING), "RUNNING→ENDING 组件请求结束");
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.SWITCHING), "RUNNING→SWITCHING 外部切换");
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.DISSOLVING), "RUNNING→DISSOLVING");
        assertTrue(SessionState.ENDING.canTransitionTo(SessionState.SWITCHING), "ENDING→SWITCHING 节拍器驱动");
        assertTrue(SessionState.ENDING.canTransitionTo(SessionState.DISSOLVING), "ENDING→DISSOLVING");
        assertTrue(SessionState.SWITCHING.canTransitionTo(SessionState.RUNNING), "SWITCHING→RUNNING attach 成功");
        assertTrue(SessionState.SWITCHING.canTransitionTo(SessionState.DISSOLVING), "SWITCHING→DISSOLVING 回退二级");

        // 非法迁移(矩阵外的格子全拒)
        assertFalse(SessionState.RUNNING.canTransitionTo(SessionState.RUNNING), "自迁移非法");
        assertFalse(SessionState.ENDING.canTransitionTo(SessionState.RUNNING), "ENDING 只能走向切换/解散");
        assertFalse(SessionState.SWITCHING.canTransitionTo(SessionState.ENDING));
        assertFalse(SessionState.SWITCHING.canTransitionTo(SessionState.SWITCHING));
        assertFalse(SessionState.DISSOLVING.canTransitionTo(SessionState.RUNNING), "DISSOLVING 终态无出边");
        assertFalse(SessionState.DISSOLVING.canTransitionTo(SessionState.DISSOLVING));
    }

    /** 既有空表(2.1 断言迁空后保留原 helper 的编译面;空列表断言口径见 ★31)。 */
    @Test
    void sessionSpecDefaultsToEmptySessionComponents() {
        assertTrue(spec("x").sessionComponents().isEmpty(),
                "三参便捷构造 = 空会话组件表(rotation 拓扑口径)");
        var withComponent = new SessionSpec(new SessionId("y"), "m", List.of(),
                List.of(new com.hydrokarbs.testkit.SessionProbeComponent.Spec(new ArrayList<>())));
        assertEquals(1, withComponent.sessionComponents().size(), "四参构造携带组件表(防御拷贝后不可变)");
        assertThrows(UnsupportedOperationException.class,
                () -> withComponent.sessionComponents().clear(), "组件表暴露不可变视图");
    }
}
