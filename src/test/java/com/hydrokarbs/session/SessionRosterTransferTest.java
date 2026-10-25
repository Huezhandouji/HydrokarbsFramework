package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.instance.Participant;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionMember;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionRoster;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SessionState;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.engine.port.JoinDecision;

/**
 * ★16「名册零搬运」名册侧首建(2.1 施工「测试与验收」表;2.3 扩全链路):
 * 切换前后 roster 同一实例、成员集合恒等(含 DISCONNECTED)。
 *
 * <p>位移的另一半:每局 toParticipants() 新造参赛者视图——名册不动、参与者重建。
 * 名册边界语义(并入本类):join 幂等 / 未断线 markReconnected no-op /
 * remove 不存在 no-op / all() 不可变。零 Bukkit import。
 */
class SessionRosterTransferTest {

    private static Session newSession() {
        return new Session(new SessionSpec(new SessionId("s1"), "transfer-probe", List.of()),
                SessionPolicy.defaults(SessionPolicy.Topology.ROTATION));
    }

    /** ①②★16:join 5 人(1 人断线)→ 连调两次 toParticipants 模拟两局 → 名册恒等。 */
    @Test
    void rosterInstanceAndMembershipInvariantAcrossRounds() {
        Session session = newSession();
        SessionRoster roster = session.roster();
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            uuids.add(UUID.randomUUID());
        }
        for (int i = 0; i < uuids.size(); i++) {
            roster.join(uuids.get(i), 1_000L + i);
        }
        roster.markDisconnected(uuids.get(0), 2_000L);
        List<SessionMember> before = roster.all();
        assertEquals(5, roster.size(), "前置:5 人在册");
        assertEquals(4, roster.onlineCount(), "前置:1 人断线,4 人在线");

        Map<UUID, Participant> roundOne = roster.toParticipants();   // 模拟第一局位移
        Map<UUID, Participant> roundTwo = roster.toParticipants();   // 模拟第二局位移

        assertSame(roster, session.roster(), "★16 切换前后 roster 同一实例(原地位移 = 对象恒等,非数据拷贝)");
        assertEquals(before, roster.all(), "成员集合恒等(记录级逐项相等,加入序不变)");
        assertTrue(roster.contains(uuids.get(0)), "DISCONNECTED 成员仍在册(宽限期内不移除)");
        assertEquals(SessionMember.ConnectionStatus.DISCONNECTED,
                roster.all().get(0).connection(), "断线成员连接态保持 DISCONNECTED");
        assertEquals(4, roster.onlineCount(), "在线数不变");
        assertEquals(5, roundOne.size(), "两局位移的参赛者视图都覆盖全体成员");
        assertEquals(5, roundTwo.size());
    }

    /** ③ remove 后集合随之收缩(真正移除口;宽限期满清点或显式离开时调)。 */
    @Test
    void removeShrinksRoster() {
        SessionRoster roster = new SessionRoster();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        roster.join(u1, 1L);
        roster.join(u2, 2L);

        roster.remove(u1);

        assertFalse(roster.contains(u1));
        assertTrue(roster.contains(u2));
        assertEquals(1, roster.size());
        assertEquals(List.of(u2), roster.all().stream().map(SessionMember::uuid).toList());
    }

    /** ④ 每局新造参赛者:两局结果互不为同一对象、uuid 集与名册一致、初始标记 JOINING。 */
    @Test
    void participantsAreFreshObjectsPerRoundMatchingRosterUuids() {
        SessionRoster roster = new SessionRoster();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        roster.join(u1, 1L);
        roster.join(u2, 2L);

        Map<UUID, Participant> roundOne = roster.toParticipants();
        Map<UUID, Participant> roundTwo = roster.toParticipants();

        assertEquals(roster.all().stream().map(SessionMember::uuid).collect(HashSet::new, Set::add, Set::addAll),
                roundOne.keySet(), "参赛者 uuid 集与名册一致");
        assertEquals(roundOne.keySet(), roundTwo.keySet());
        for (UUID uuid : roundOne.keySet()) {
            Participant first = roundOne.get(uuid);
            Participant second = roundTwo.get(uuid);
            assertNotNull(first);
            assertNotNull(second);
            assertNotSame(first, second, "每局新造参赛者(实例侧换血,名册不动)");
            assertNotEquals(first, second);
            assertEquals(Participant.ParticipantState.JOINING, first.state(), "初始标记 JOINING(§3.2-B4)");
        }
    }

    /** 名册边界:重复 join 幂等不重复计;未断线 markReconnected no-op;remove 不存在 no-op;all() 不可变。 */
    @Test
    void rosterBoundarySemantics() {
        SessionRoster roster = new SessionRoster();
        UUID member = UUID.randomUUID();
        roster.join(member, 5L);

        roster.join(member, 99L);                       // 在册即幂等返回:不重复计、不覆盖首登时间
        assertEquals(1, roster.size(), "重复 join 幂等不重复计");
        assertEquals(5L, roster.all().get(0).joinedAtMillis(), "幂等 = 不动既有条目");

        roster.markReconnected(member);                 // 未断线成员:无状态可恢复
        assertEquals(SessionMember.ConnectionStatus.ONLINE, roster.all().get(0).connection(),
                "对未断线成员 markReconnected 为 no-op");

        roster.remove(UUID.randomUUID());               // 不存在的 uuid
        assertEquals(1, roster.size(), "remove 不存在的 uuid 为 no-op");

        List<SessionMember> view = roster.all();
        assertThrows(UnsupportedOperationException.class, () -> view.clear(),
                "all() 不可变(外部不得绕过名册方法改成员)");
    }

    /** 断线标记不移除在册身份:markDisconnected 保留 joinedAtMillis;宽限期内 disconnectedBefore 不点名。 */
    @Test
    void disconnectedMemberStaysOnRosterUntilGraceExpires() {
        SessionRoster roster = new SessionRoster();
        UUID member = UUID.randomUUID();
        roster.join(member, 100L);

        roster.markDisconnected(member, 5_000L);

        assertTrue(roster.contains(member), "markDisconnected 不移除(宽限期内成员保留)");
        assertEquals(SessionMember.ConnectionStatus.DISCONNECTED, roster.all().get(0).connection());
        assertEquals(100L, roster.all().get(0).joinedAtMillis(), "断线标记保留首登时间");
        assertEquals(0, roster.onlineCount());
        assertTrue(roster.disconnectedBefore(5_000L + 59_999L, 60_000L).isEmpty(),
                "宽限 60s 内 disconnectedBefore 不点名");
        assertEquals(List.of(member), roster.disconnectedBefore(5_000L + 60_000L, 60_000L),
                "宽限期满 disconnectedBefore 点名(供清点)");

        roster.markReconnected(member);
        assertEquals(SessionMember.ConnectionStatus.ONLINE, roster.all().get(0).connection(),
                "重连恢复 ONLINE");
        assertTrue(roster.disconnectedBefore(5_000L + 120_000L, 60_000L).isEmpty(),
                "重连销账后不再点名");
    }

    // ── ★16 全链路扩展(2.3):经真实 switchMode 的 B1~B5 验证名册零搬运 ────

    /** ★16:真实切换管线后名册同一、成员恒等(含断线宽限成员)、参赛者与名册一致;切换窗口拒重入。 */
    @Test
    void rosterInvariantThroughRealSwitchPipeline() throws Exception {
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("alpha", 0, 100),
                SwitchHarness.mode("beta", 0, 100));
        Session session = harness.createSession("transfer", "alpha");
        SessionRoster roster = session.roster();

        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            uuids.add(UUID.randomUUID());
            assertEquals(JoinResult.Joined.class,
                    harness.service.join(session.id(), uuids.get(i)).getClass(), "名册登记成功");
        }
        harness.service.leave(uuids.get(0), LeaveReason.QUIT);   // 1 人断线:DISCONNECTED 保留在册
        List<SessionMember> before = roster.all();
        assertEquals(4, roster.size(), "前置:4 人在册(1 人断线宽限中)");

        for (int i = 0; i < 101; i++) {
            session.idleTimers();                  // 推进最短驻留时钟(真实宽限 60s 未到,断线成员保留)
        }
        var future = harness.service.switchMode(session.id(),
                new MatchModeSelection("beta", Optional.empty(), true), Handoff.empty());
        assertEquals(SessionState.SWITCHING, session.state(), "切换窗口已开");
        var second = harness.service.switchMode(session.id(),
                new MatchModeSelection("beta", Optional.empty(), true), Handoff.empty()).get();
        assertEquals(SwitchResult.Reason.SWITCH_IN_PROGRESS,
                assertInstanceOf(SwitchResult.Rejected.class, second).code(), "窗口内第二请求被拒");

        harness.ticker.tickAll();
        harness.ticker.tickAll();
        assertEquals("beta", assertInstanceOf(SwitchResult.Succeeded.class,
                future.get(2, java.util.concurrent.TimeUnit.SECONDS)).toMode(), "切换成功到 beta");

        assertSame(roster, session.roster(), "★16 切换前后 roster 同一实例");
        assertEquals(before, roster.all(), "成员集合恒等(记录级,含 DISCONNECTED 宽限成员)");
        assertEquals(SessionMember.ConnectionStatus.DISCONNECTED, roster.all().stream()
                        .filter(m -> m.uuid().equals(uuids.get(0))).findFirst().orElseThrow().connection(),
                "断线成员连接态保持");
        Set<UUID> rosterUuids = roster.all().stream().map(SessionMember::uuid)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(rosterUuids,
                session.currentInstance().orElseThrow().participants().stream()
                        .map(Participant::uuid).collect(java.util.stream.Collectors.toSet()),
                "新实例参赛者 uuid 集与名册一致(名册原地位移)");
    }

    /** 触发点②(运行中加入):经声明序第一个裁决实现者;REJECT 不收、名册不登记。 */
    @Test
    void midJoinAdmissionGateAdmitsSpectatesAndRejects() {
        LinkedHashMap<String, com.hydrokarbs.engine.component.MatchModeComponent.Specification<?>> specs =
                new LinkedHashMap<>();
        specs.put("gate", new com.hydrokarbs.testkit.AdmissionGateComponent.Spec(
                player -> player.equals(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                        ? new JoinDecision(JoinDecision.Kind.SPECTATE, "观战位")
                        : player.equals(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                                ? new JoinDecision(JoinDecision.Kind.REJECT, "对局进行中")
                                : new JoinDecision(JoinDecision.Kind.ADMIT, null)));
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("gated", 0, 100, specs));
        Session session = harness.createSession("admission", "gated");

        UUID admit = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID spectate = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID reject = UUID.fromString("00000000-0000-0000-0000-000000000003");

        assertEquals(JoinResult.Joined.class, harness.service.join(session.id(), admit).getClass());
        assertTrue(session.currentInstance().orElseThrow().participant(admit).isPresent(),
                "ADMIT 并入当前实例(参赛位)");
        assertEquals(JoinResult.Joined.class, harness.service.join(session.id(), spectate).getClass());
        assertTrue(session.currentInstance().orElseThrow().participant(spectate).isPresent(),
                "SPECTATE 并入当前实例(观战位,会话层只记名册)");
        JoinResult.Rejected refused = assertInstanceOf(JoinResult.Rejected.class,
                harness.service.join(session.id(), reject));
        assertTrue(refused.detail().contains("对局进行中"), "拒绝 detail 带人读原因: " + refused.detail());
        assertFalse(session.roster().contains(reject), "REJECT 名册不登记");
        assertEquals(2, session.roster().size());
    }

    /** 触发点①(初始摆放):REJECT 的成员留在名册、不进新实例。 */
    @Test
    void initialPlacementGateFiltersParticipantsButKeepsRoster() throws Exception {
        LinkedHashMap<String, com.hydrokarbs.engine.component.MatchModeComponent.Specification<?>> specs =
                new LinkedHashMap<>();
        specs.put("gate", new com.hydrokarbs.testkit.AdmissionGateComponent.Spec(
                player -> player.getLeastSignificantBits() % 2 == 0
                        ? new JoinDecision(JoinDecision.Kind.REJECT, "本局满员")
                        : new JoinDecision(JoinDecision.Kind.ADMIT, null)));
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("plain", 0, 100),
                SwitchHarness.mode("gated", 0, 100, specs));
        Session session = harness.createSession("place", "plain");
        UUID odd = UUID.fromString("00000000-0000-0000-0000-000000000011");   // 尾数奇:ADMIT
        UUID even = UUID.fromString("00000000-0000-0000-0000-000000000012");  // 尾数偶:REJECT
        harness.service.join(session.id(), odd);
        harness.service.join(session.id(), even);
        for (int i = 0; i < 101; i++) {
            session.idleTimers();
        }
        harness.service.switchMode(session.id(),
                new MatchModeSelection("gated", Optional.empty(), true), Handoff.empty());
        harness.ticker.tickAll();
        harness.ticker.tickAll();

        assertEquals(2, session.roster().size(), "REJECT 成员仍在名册(会话不丢人)");
        var participants = session.currentInstance().orElseThrow().participants();
        assertEquals(Set.of(odd), participants.stream().map(Participant::uuid)
                        .collect(java.util.stream.Collectors.toSet()),
                "新实例只含 ADMIT 成员(触发点①逐人裁决)");
    }
}
