package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.session.ConnectionTracker;

/**
 * 断线宽限记账(2.1 施工「测试与验收」表;§3.1 连接追踪行;§15.2 默认 60s):
 * 宽限内清点不返回、到点返回;重连销账;各成员独立判定、互不影响;
 * 清点销账(同批不重复返回)。零 Bukkit import。
 */
class ConnectionTrackerTest {

    private static final long GRACE = 60_000L;
    private static final long T0 = 1_000_000L;

    /** 宽限 60s 内 sweep 不返回该成员;到点即返回。 */
    @Test
    void sweepReturnsMemberOnlyAfterGraceElapsed() {
        ConnectionTracker tracker = new ConnectionTracker(GRACE);
        UUID member = UUID.randomUUID();
        tracker.onDisconnect(member, T0);

        assertTrue(tracker.sweep(T0 + 59_999L).isEmpty(), "宽限 60s 内(59_999ms)不返回");
        assertEquals(List.of(member), tracker.sweep(T0 + 60_000L), "到点(60_000ms)即宽限耗尽,返回");
    }

    /** onReconnect 销账:从宽限表消失,且此后任何时点 sweep 都不再返回。 */
    @Test
    void reconnectClearsGraceRecord() {
        ConnectionTracker tracker = new ConnectionTracker(GRACE);
        UUID member = UUID.randomUUID();
        tracker.onDisconnect(member, T0);

        tracker.onReconnect(member);

        assertTrue(tracker.sweep(T0 + 120_000L).isEmpty(),
                "重连销账后,远超宽限的时点也不再返回");
    }

    /** 两成员先后断线,宽限互不影响(各自按各自断线时刻判定)。 */
    @Test
    void membersGraceWindowsAreIndependent() {
        ConnectionTracker tracker = new ConnectionTracker(GRACE);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        tracker.onDisconnect(first, T0);
        tracker.onDisconnect(second, T0 + 30_000L);

        assertEquals(List.of(first), tracker.sweep(T0 + 60_000L),
                "先断线者到点被清点;后断线者仍在宽限内");

        assertEquals(List.of(second), tracker.sweep(T0 + 90_000L),
                "后断线者自己的 60s 到点才被清点(与前者的清场互不影响)");
        assertTrue(tracker.sweep(T0 + 1_000_000L).isEmpty(), "宽限表已空");
    }

    /** sweep 销账:已清点过的成员不因时间继续推进而被重复返回。 */
    @Test
    void sweepDoesNotReturnSameMemberTwice() {
        ConnectionTracker tracker = new ConnectionTracker(GRACE);
        UUID member = UUID.randomUUID();
        tracker.onDisconnect(member, T0);

        assertEquals(List.of(member), tracker.sweep(T0 + GRACE));
        assertTrue(tracker.sweep(T0 + GRACE * 5).isEmpty(),
                "首次清点即销账,后续 sweep 不重复返回");
    }

    /** 重连后再断线:按最新断线时刻重新计宽限。 */
    @Test
    void reconnectThenDisconnectRestartsGrace() {
        ConnectionTracker tracker = new ConnectionTracker(GRACE);
        UUID member = UUID.randomUUID();
        tracker.onDisconnect(member, T0);
        tracker.onReconnect(member);

        long second = T0 + 300_000L;
        tracker.onDisconnect(member, second);

        assertTrue(tracker.sweep(second + 59_999L).isEmpty());
        assertEquals(List.of(member), tracker.sweep(second + 60_000L),
                "再断线覆盖为最新断线时刻,宽限重新起算");
    }

    /** 默认宽限 = 60s(§15.2)。 */
    @Test
    void defaultGraceIsSixtySeconds() {
        assertEquals(ConnectionTracker.DEFAULT_GRACE_MILLIS, new ConnectionTracker().graceMillis());
    }
}
