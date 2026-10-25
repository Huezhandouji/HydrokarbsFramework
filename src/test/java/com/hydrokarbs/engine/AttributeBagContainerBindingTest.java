package com.hydrokarbs.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.instance.LifecycleDriver;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.instance.Participant;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.SessionMember;
import com.hydrokarbs.testkit.OfflineAssembly;

/**
 * 容器灌表回归(★26 行为腿的容器侧闭环;阶段 1 §7 问题 3 的防回归闸):
 * {@link AttributeBag#bindComponent} 曾为包私有,唯一合法灌表方(实例容器,
 * 住 engine.instance 包)编译期调不到——授权表恒空,组件经
 * {@code svc().self().attrs().set(...)} 写自己命名空间也会被开发期断言误拒
 * (阶段 1 组件层只有读路径故未触发)。裁决 A 案:bindComponent 提 public +
 * 容器装配期统一灌表(实例袋 + 各参赛者袋)。本测试经真容器(OfflineAssembly
 * 离线装配路径)验证灌表生效:自命名空间写通过、他人命名空间照旧拦截。
 *
 * <p>零 Bukkit import(假件全部来自 testkit 离线装配路径);测试文案中文。
 */
class AttributeBagContainerBindingTest {

    // ── 假件:alpha 探针(写路径)+ beta 异类占位 ───────────────────

    /** 假组件 alpha(命名空间 = id "alpha"):经 svc() 写实例袋/参赛者袋的探针。 */
    static final class AlphaComponent extends MatchModeComponent {

        AlphaComponent(String id, MatchModeInstanceServices services) {
            super(id, services);
        }

        /** 写自己命名空间的实例级键——修复前授权表恒空,此写法会被误拒(断言①的路径)。 */
        void writeOwnInstanceAttr() {
            svc().self().attrs().set(AttributeBag.Key.of(id(), "greeting", String.class), "hi-alpha");
        }

        /** 写他人命名空间("beta")的实例级键——必须被开发期断言拦截(断言②的路径)。 */
        void writeForeignInstanceAttr() {
            svc().self().attrs().set(AttributeBag.Key.of("beta", "greeting", String.class), "intruder");
        }

        /** 写自己命名空间的参赛者级键(断言③的路径)。 */
        void writeOwnParticipantAttr(Participant participant) {
            participant.attrs().set(AttributeBag.Key.of(id(), "seat", Integer.class), 7);
        }

        /** 参赛者快照(svc 受保护口开放给测试,与 TestComponent 的观察包装同构)。 */
        List<Participant> participants() {
            return svc().self().participants();
        }
    }

    /** 假组件 beta(命名空间 = id "beta"):与 alpha 异类,保证异常消息的 id→类反查精确。 */
    static final class BetaComponent extends MatchModeComponent {

        BetaComponent(String id, MatchModeInstanceServices services) {
            super(id, services);
        }
    }

    /** alpha 的装配描述符。 */
    static final class AlphaSpec extends MatchModeComponent.Specification<AlphaComponent> {

        AlphaSpec() {
            super("alpha-probe");
        }

        @Override
        public AlphaComponent create(String id, MatchModeInstanceServices services) {
            return new AlphaComponent(id, services);
        }
    }

    /** beta 的装配描述符。 */
    static final class BetaSpec extends MatchModeComponent.Specification<BetaComponent> {

        BetaSpec() {
            super("beta-probe");
        }

        @Override
        public BetaComponent create(String id, MatchModeInstanceServices services) {
            return new BetaComponent(id, services);
        }
    }

    // ── 装配:真容器 + 离线假件(测试地图/缺省模式参数/空交接/假引擎服务/会话壳) ──

    private record Assembly(MatchModeInstance instance, AlphaComponent alpha) {
    }

    private static Assembly assemble() {
        LinkedHashMap<String, MatchModeComponent.Specification<?>> specs = new LinkedHashMap<>();
        specs.put("alpha", new AlphaSpec());
        specs.put("beta", new BetaSpec());
        MatchMode mode = OfflineAssembly.mode("binding_mode", specs);
        OfflineAssembly.RecordingSession session = new OfflineAssembly.RecordingSession("s1",
                new SessionMember(UUID.randomUUID(), SessionMember.ConnectionStatus.ONLINE, 1L));
        MatchModeInstance instance =
                OfflineAssembly.instance(mode, session, new OfflineAssembly.FakePlatform());
        return new Assembly(instance, instance.component(AlphaComponent.class));
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    void instanceBagLetsComponentWriteOwnNamespaceAfterContainerBinding() {
        Assembly a = assemble();

        // 断言①:修复前授权表恒空,组件写自己命名空间即被误拒——本断言就是防回归
        assertDoesNotThrow(a.alpha()::writeOwnInstanceAttr,
                "容器已灌授权表:组件写自己命名空间(实例袋)不得被误拒");
        assertEquals(Optional.of("hi-alpha"),
                a.instance().view().attr(AttributeBag.Key.of("alpha", "greeting", String.class)),
                "自命名空间写入成功且经只读读口可读回");
    }

    @Test
    void instanceBagStillRejectsForeignNamespaceWithNamedCulpritAndKey() {
        Assembly a = assemble();

        // 断言②:灌表是白名单而不是关断言——写他人命名空间必须照旧拦截
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                a.alpha()::writeForeignInstanceAttr,
                "组件写他人命名空间必须被开发期断言拦截");
        String message = String.valueOf(ex.getMessage());
        assertTrue(message.contains("id=alpha"),
                "异常消息点名调用组件(alpha),实际:" + message);
        assertTrue(message.contains("namespace=beta"),
                "异常消息点名键的 namespace(beta),实际:" + message);
    }

    @Test
    void participantBagLetsComponentWriteOwnNamespaceAfterContainerBinding() {
        Assembly a = assemble();
        LifecycleDriver.begin(a.instance());   // startAll:名册位移 + 容器为参赛者袋补灌授权表
        List<Participant> participants = a.alpha().participants();
        assertEquals(1, participants.size(), "名册原地位移后恰有一名参赛者");

        // 断言③:参赛者袋由名册在容器外创建,容器放置时补灌——组件写自己命名空间不被误拒
        Participant participant = participants.get(0);
        assertDoesNotThrow(() -> a.alpha().writeOwnParticipantAttr(participant),
                "容器放置参赛者时已补灌授权表:组件写自己命名空间(参赛者袋)不得被误拒");
        assertEquals(Optional.of(7),
                participant.attrs().get(AttributeBag.Key.of("alpha", "seat", Integer.class)),
                "参赛者袋自命名空间写入成功且可读回");
    }
}
