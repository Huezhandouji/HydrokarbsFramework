package com.hydrokarbs.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hydrokarbs.engine.port.JoinDecision;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionMember;
import com.hydrokarbs.testkit.AdmissionGateComponent;
import com.hydrokarbs.testkit.FakeWorldPort;
import com.hydrokarbs.testkit.MapTestKit;
import com.hydrokarbs.testkit.OfflineApi;
import com.hydrokarbs.testkit.TestPlugins;

/**
 * 成员操作与能力标签的 api 面端到端(★42 + D4;7.5 施工文档步骤 5;离线):
 * {@code joinSession}/{@code leaveSession} 完成「入局/出局/Rejected 兜底」全链
 * (与 /sm 同权的受控暴露,D2——委托 {@code SessionService#join/leave},含
 * {@code JoinAdmission} 裁决);{@code registerCapability}/{@code capabilities}
 * 的注册、幂等、空白拒绝与提供方 disable 摘除(D4;引擎机制件 tag-only 形态不动,
 * 7.5 决策 5)。
 *
 * <p>真会话服务装配见 {@link OfflineApi}(假世界口/直通主线程;零 Bukkit 静态调用,
 * 提供方身份键用 {@link TestPlugins} 代理)。
 */
class MiniGameApiMembersTest {

    private static final String MODE_ID = "probe_mode";
    private static final String GATED_MODE_ID = "gated_mode";

    @TempDir
    Path tempDir;

    private OfflineApi harness;
    private Plugin provider;

    @BeforeEach
    void setUp() {
        // gated_mode:入场裁决恒拒(Rejected 兜底链路的实例侧门)
        harness = new OfflineApi(templatesRoot(), FakeWorldPort.immediate(),
                OfflineApi.mode(MODE_ID, OfflineApi.TAG),
                gatedMode(GATED_MODE_ID));
        // 注册式地图(标签 common):让首切实例就位——入场裁决在实例路径上才有门
        // (★文件式装载通道已删,造数走 MapTestKit 注册 fixture)
        MapTestKit.registered(harness.maps, templatesRoot(), "file_map", OfflineApi.TAG);
        provider = TestPlugins.named("members-plugin");
    }

    /** 模板根镜像生产行形状:<数据目录>/maps/templates(20-D2 约定)。 */
    private Path templatesRoot() {
        return tempDir.resolve("maps").resolve("templates");
    }

    // ── ★42 joinSession/leaveSession 端到端 ──────────────────────────

    @Test
    void joinSessionRegistersMemberAndReturnsJoined() {
        harness.createSession("s1", MODE_ID);
        UUID alice = UUID.randomUUID();

        JoinResult result = harness.api.joinSession("s1", alice);

        assertInstanceOf(JoinResult.Joined.class, result, "空会话(无实例/名册空)入局成功");
        Session session = harness.service.session(new com.hydrokarbs.engine.session.SessionId("s1"))
                .orElseThrow();
        assertTrue(session.roster().contains(alice), "名册登记");
        assertEquals(List.of(alice),
                session.roster().all().stream().map(SessionMember::uuid).toList());
    }

    @Test
    void joinMissingSessionReturnsRejected() {
        JoinResult result = harness.api.joinSession("ghost", UUID.randomUUID());
        JoinResult.Rejected rejected = assertInstanceOf(JoinResult.Rejected.class, result,
                "会话不存在 → Rejected(引擎裁决原样透传,§3.2)");
        assertEquals("session-not-found", rejected.reason(), "缺会话拒绝码(引擎既有口径)");
        assertTrue(rejected.detail().contains("ghost"), "detail 点名");
    }

    @Test
    void joinRejectedByAdmissionGateBubblesThroughApi() {
        harness.createSession("s1", GATED_MODE_ID);
        UUID alice = UUID.randomUUID();

        JoinResult result = harness.api.joinSession("s1", alice);

        JoinResult.Rejected rejected = assertInstanceOf(JoinResult.Rejected.class, result,
                "实例入场裁决拒绝 → Rejected(★42 Rejected 兜底,D2 受控暴露)");
        assertEquals("rejected-by-mode", rejected.reason());
        assertTrue(rejected.detail().contains("测试拒绝"), "裁决原因透传: " + rejected.detail());
        harness.createSession("s2", MODE_ID);      // 框架照常(拒绝是裁决,不是故障)
        assertFalse(harness.api.sessions().isEmpty());
    }

    @Test
    void leaveSessionRemovesMemberAndIsIdempotent() {
        harness.createSession("s1", MODE_ID);
        UUID alice = UUID.randomUUID();
        harness.api.joinSession("s1", alice);

        harness.api.leaveSession(alice, LeaveReason.KICK);   // KICK = 立即移除(§3.2)
        Session session = harness.service.session(new com.hydrokarbs.engine.session.SessionId("s1"))
                .orElseThrow();
        assertFalse(session.roster().contains(alice), "被踢成员移出名册");

        assertDoesNotThrow(() -> harness.api.leaveSession(alice, LeaveReason.KICK),
                "不在任何会话 = no-op(幂等,D2)");
    }

    @Test
    void leaveWithQuitKeepsMemberInGraceWindow() {
        harness.createSession("s1", MODE_ID);
        UUID alice = UUID.randomUUID();
        harness.api.joinSession("s1", alice);

        harness.api.leaveSession(alice, LeaveReason.QUIT);

        Session session = harness.service.session(new com.hydrokarbs.engine.session.SessionId("s1"))
                .orElseThrow();
        assertTrue(session.roster().contains(alice),
                "QUIT = 断线宽限标记,成员保留在册(ConnectionTracker 口径,§3.1)");
    }

    // ── D4 能力标签:注册/幂等/空白拒绝/提供方 disable 摘除 ─────────────

    @Test
    void capabilityRegistrationIsVisibleAndIdempotent() {
        harness.api.registerCapability(provider, "their-capability");
        harness.api.registerCapability(provider, "their-capability");   // 幂等

        assertTrue(harness.api.capabilities().contains("their-capability"),
                "capabilities() 可见(D4)");
        harness.api.registerCapability(TestPlugins.named("other-plugin"), "other-capability");
        assertEquals(Set.of("their-capability", "other-capability"),
                Set.copyOf(harness.api.capabilities()), "多提供方标签并集");
    }

    @Test
    void capabilityDisableRemovesTagsOfThatProviderOnly() {
        Plugin first = TestPlugins.named("first-plugin");
        Plugin second = TestPlugins.named("second-plugin");
        harness.api.registerCapability(first, "shared-tag");
        harness.api.registerCapability(first, "first-only");
        harness.api.registerCapability(second, "shared-tag");           // 多提供方主张同一标签

        harness.api.unregisterCapabilitiesOf(first);                    // PluginDisableEvent 兜底路径

        assertTrue(harness.api.capabilities().contains("shared-tag"),
                "仍被其他提供方主张的标签补回(D4 归属映射语义)");
        assertFalse(harness.api.capabilities().contains("first-only"),
                "该提供方独有标签摘除");

        harness.api.unregisterCapabilitiesOf(second);
        assertFalse(harness.api.capabilities().contains("shared-tag"), "最后主张者下线 → 摘除");
    }

    @Test
    void blankCapabilityTagIsLoudlyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> harness.api.registerCapability(provider, "  "),
                "空白标签响亮抛(引擎机制件既有校验,7.5 决策 5)");
    }

    /** 恒拒入场裁决的模式(Rejected 兜底链路;testkit AdmissionGateComponent)。 */
    private static com.hydrokarbs.engine.mode.MatchMode gatedMode(String id) {
        return new com.hydrokarbs.engine.mode.MatchMode.Builder(id)
                .mapTags(OfflineApi.TAG)
                .meta(new com.hydrokarbs.engine.mode.MatchModeMeta(Set.of(), false, 0, 100,
                        com.hydrokarbs.engine.mode.MatchModeMeta.DefaultTransition.STAY))
                .addComponent("gate", new AdmissionGateComponent.Spec(
                        player -> new JoinDecision(JoinDecision.Kind.REJECT, "测试拒绝")))
                .build();
    }
}
