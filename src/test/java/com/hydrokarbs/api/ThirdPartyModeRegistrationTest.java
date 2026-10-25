package com.hydrokarbs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.component.SessionComponentContributionRegistry;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.port.ConfigView;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.CapabilityRegistry;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.EndResult;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.api.view.MatchModeInfo;
import com.hydrokarbs.listener.ProviderServiceListener;
import com.hydrokarbs.map.MapDefinition;
import com.hydrokarbs.map.MapRegistry;
import com.hydrokarbs.testkit.OfflineAssembly;
import com.hydrokarbs.testkit.SessionProbeComponent;
import com.hydrokarbs.testkit.TestComponent;

/**
 * 跨插件模式可注册(★20 首建;7.2 施工文档「测试与验收」五组断言,离线)。
 * fake 插件的工厂经注册面收录后:出现在目录、可被 switchMode 选中、卸载即摘除
 * 且受影响会话回退(§16.2-★20 原文口径)。
 *
 * <ol>
 *   <li>①合法 fake provider → {@code registerProvider} → 注册表目录 +
 *       7.1 Impl {@code modes()} 含对应 {@link MatchModeInfo};</li>
 *   <li>②可被 switchMode 选中:经公共面 {@code switchMode} 发起(fake SessionService
 *       记录调用;断言目标 id 与 force 语义;注册表 {@code require} 通过佐证
 *       切换阶段 A 的目标查找不拒);</li>
 *   <li>③坏 provider(缺必需依赖 / build 抛 / 重复 id)不进注册表 + SEVERE
 *       日志断言(§2.6 运行期口径:不上抛,Logger 注入构造口先例 =
 *       {@code MatchModeRegistry});</li>
 *   <li>④{@code unregisterProvider} 目录摘除 + 受影响会话恰一次回退切换
 *       (fallback 读 {@code session.fallback-mode} 配置、force 豁免,§13.4/§3.5);
 *       fallback 空(★v1.3 R3)/未注册 → 恰一次 {@code FALLBACK_DISSOLVE};
 *       {@code Rejected(SWITCH_IN_PROGRESS)} → WARN 放弃不自旋(§14.17);</li>
 *   <li>⑤主仓生产源零第三方示例字面量 {@code their_mode}(§14.13:收录是
 *       「注册表被动收」,示例只住 examples/TheirPlugin;§16.2-4 禁词精神)。</li>
 * </ol>
 *
 * <p><b>事件路径联调口径(7.2 施工文档测试与验收第 5 条)</b>:
 * {@code ServiceRegisterEvent}/{@code ServiceUnregisterEvent} 全链需活服派发,
 * 离线以公开内核 {@code ingestProvider}/{@code removeProvider} 代测(Bukkit 事件
 * 方法只是一层类型过滤薄壳)+ 构造期收录源注入口代测先启补收录;
 * 事件全链由 runServer 冒烟覆盖(冒烟归后续独立阶段,不在本子阶段)。
 *
 * <p><b>⑤落定说明</b>:断言 ⑤ 以测试内源码扫描实现(随全量测试常驻门禁,而非
 * 一次性 grep);fake 模式 id 取中性 {@code probe_mode},与其分居主仓 src/(零
 * their_mode)与 examples/TheirPlugin/(their_mode 唯一住址)两侧。
 */
class ThirdPartyModeRegistrationTest {

    /** 测试用第三方模式 id(中性;their_mode 字面量只住 examples/,⑤ 守卫)。 */
    private static final String PROBE = "probe_mode";
    private static final String FALLBACK = "fallback_mode";
    private static final String FALLBACK_KEY = "session.fallback-mode";

    private final List<LogRecord> logs = new ArrayList<>();
    private final List<String> hookLog = new ArrayList<>();
    private MatchModeRegistry registry;
    private Logger logger;
    private SessionComponentContributionRegistry contributions;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger(ThirdPartyModeRegistrationTest.class.getName() + "-"
                + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        // 配置源:全回落缺省(装载链由 ConfigRepositoryTest 覆盖,此处只测注册面)
        registry = new MatchModeRegistry(id -> OfflineAssembly.cfg(), logger);
        contributions = new SessionComponentContributionRegistry();
    }

    // ── 假件 ─────────────────────────────────────────────────────

    /** 合法假工厂:组件表全部自满足(缺省零组件;人数窗 1~16 对齐 TheirPlugin 示例声明)。 */
    private static MatchModeProvider provider(String id, Set<String> mapTags,
                                              MatchModeComponent.Specification<?>... specs) {
        return new MatchModeProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public MatchMode build(MatchModeConfig cfg) {
                MatchMode.Builder builder = new MatchMode.Builder(id)
                        .mapTags(mapTags)
                        .meta(new MatchModeMeta(Set.of(), false, 1, 16,
                                MatchModeMeta.DefaultTransition.STAY));
                int index = 1;
                for (MatchModeComponent.Specification<?> spec : specs) {
                    builder.addComponent("c" + index++, spec);
                }
                return builder.build();
            }
        };
    }

    /** build 即抛的假工厂(第三方工厂代码异常的最坏形态)。 */
    private static MatchModeProvider brokenProvider(String id) {
        return new MatchModeProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public MatchMode build(MatchModeConfig cfg) {
                throw new IllegalStateException("第三方工厂 build 异常(测试注入)");
            }
        };
    }

    /** 无人提供的组件类型(制造「依赖校验失败」的坏模板;先例 MatchModeRegistryTest)。 */
    private abstract static class Unresolved extends MatchModeComponent {

        Unresolved(String id, MatchModeInstanceServices svc) {
            super(id, svc);
        }
    }

    /**
     * 当前实例固定为给定模式实例的会话桩:覆写 {@code currentInstance} 返回
     * 真实实例({@code createInstance} 造,零钩子触发——awake/start 属切换执行段,
     * 这里只供 {@code modeId()} 读口)。
     */
    private static final class FixedCurrentSession extends Session {

        private final MatchModeInstance current;

        FixedCurrentSession(String id, MatchMode mode) {
            super(new SessionSpec(new SessionId(id), mode.id(), List.of()),
                    SessionPolicy.defaults(SessionPolicy.Topology.ROTATION));
            this.current = mode.createInstance(this, OfflineAssembly.map(), OfflineAssembly.cfg(),
                    null, Handoff.empty(), new EngineServices(new OfflineAssembly.FakePlatform()));
        }

        @Override
        public Optional<MatchModeInstance> currentInstance() {
            return Optional.of(current);
        }
    }

    /** switchMode/dissolve 调用记录行。 */
    private record SwitchCall(SessionId sessionId, MatchModeSelection selection, Handoff payload) {
    }

    private record DissolveCall(SessionId sessionId, DissolveReason reason) {
    }

    /**
     * 会话服务记录桩:switchMode 记录调用并按脚本回结果(缺省 Succeeded);
     * 摘除回退编排的「恰一次/目标/force」断言面。
     */
    private static final class RecordingService implements SessionService {

        final List<SwitchCall> switches = new ArrayList<>();
        final List<DissolveCall> dissolves = new ArrayList<>();
        Collection<Session> sessions = List.of();
        SwitchResult scripted;

        void setSessions(Collection<Session> value) {
            this.sessions = value;
        }

        @Override
        public Collection<Session> all() {
            return sessions;
        }

        @Override
        public Optional<Session> session(SessionId id) {
            for (Session session : sessions) {
                if (session.id().equals(id)) {
                    return Optional.of(session);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Session> sessionOf(UUID player) {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<SwitchResult> switchMode(SessionId id, MatchModeSelection target,
                                                          Handoff payload) {
            switches.add(new SwitchCall(id, target, payload));
            SwitchResult result = scripted != null ? scripted
                    : new SwitchResult.Succeeded(id, "from", target.modeId(), EndResult.nowhere());
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public Session create(SessionSpec spec) {
            throw new UnsupportedOperationException("测试桩不消费");
        }

        @Override
        public JoinResult join(SessionId id, UUID player) {
            throw new UnsupportedOperationException("测试桩不消费");
        }

        @Override
        public void leave(UUID player, LeaveReason reason) {
            // 测试桩不消费
        }

        @Override
        public void dissolve(SessionId id, DissolveReason reason) {
            dissolves.add(new DissolveCall(id, reason));
        }
    }

    /** fallback 配置视图:仅 {@code session.fallback-mode} 可读,其余全回落(泛型视图像素级对齐实现口径)。 */
    private static ConfigView configWithFallback(String fallbackMode) {
        return new ConfigView() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(String path, T def) {
                return path.equals(FALLBACK_KEY) ? (T) fallbackMode : def;
            }
        };
    }

    private boolean hasSevere(String fragment) {
        return logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE
                && String.valueOf(r.getMessage()).contains(fragment));
    }

    /** 参数化日志行取格式化文本(java.util.logging 的 {0} 占位需 MessageFormat 展开)。 */
    private static String formatted(java.util.logging.LogRecord record) {
        Object[] params = record.getParameters();
        return params == null ? String.valueOf(record.getMessage())
                : java.text.MessageFormat.format(record.getMessage(), params);
    }

    // ── ① 合法 provider → 目录 + api 视图 ────────────────────────

    @Test
    void registeredProviderEntersCatalogAndApiView() {
        registry.registerProvider(provider(PROBE, Set.of("demo")));

        assertEquals(PROBE, registry.require(PROBE).id(), "注册表目录含其 id(§16.2-★20)");
        assertTrue(registry.all().stream().anyMatch(mode -> mode.id().equals(PROBE)));

        // 经 7.1 Impl modes():第三方模式入目录后服主可见(7.2 javadoc 句;§7.1)
        MiniGameApiImpl api = new MiniGameApiImpl(new RecordingService(), registry,
                new MapRegistry(), new CapabilityRegistry(), new SessionEventBus(),
                new SessionComponentContributionRegistry(), Runnable::run);
        Optional<MatchModeInfo> info = api.mode(PROBE);
        assertTrue(info.isPresent(), "mode(id) 命中第三方模式");
        assertEquals(PROBE, info.get().id());
        assertTrue(api.modes().stream().anyMatch(mode -> mode.id().equals(PROBE)),
                "modes() 与出厂/其他模式并列、无特判顺序(14.21)");
    }

    // ── ② 可被 switchMode 选中 ───────────────────────────────────

    @Test
    void thirdPartyModeSelectableThroughApiSwitch() {
        MatchModeProvider probe = provider(PROBE, Set.of("demo"));
        registry.registerProvider(probe);

        // 切换阶段 A 目标查找口(require,§3.2-A1)对第三方 id 不拒——「可被选中」的静态前提
        assertDoesNotThrow(() -> registry.require(PROBE));

        RecordingService fake = new RecordingService();
        fake.setSessions(List.of(OfflineAssembly.session("s1")));
        MiniGameApiImpl api = new MiniGameApiImpl(fake, registry, new MapRegistry(),
                new CapabilityRegistry(), new SessionEventBus(),
                new SessionComponentContributionRegistry(), Runnable::run);

        Handoff payload = Handoff.empty();
        CompletableFuture<SwitchResult> future = api.switchMode("s1",
                new MatchModeSelection(PROBE, Optional.empty(), true), payload);

        SwitchResult result = future.join();                       // 直接执行器 → 内联完成
        assertInstanceOf(SwitchResult.Succeeded.class, result, "fake 回 Succeeded(★20 口径)");
        assertEquals(1, fake.switches.size(), "恰一次委托");
        SwitchCall call = fake.switches.get(0);
        assertEquals(new SessionId("s1"), call.sessionId(), "目标会话正确");
        assertEquals(PROBE, call.selection().modeId(), "目标 id = 第三方模式");
        assertTrue(call.selection().force(), "force 语义原样透传(§3.5 外部调用方显式选择)");
        assertSame(payload, call.payload(), "交接载荷原样透传");
    }

    // ── ③ 坏 provider:SEVERE + 跳过 + 不进注册表 ─────────────────

    @Test
    void dependencyFailureIsSevereSkippedAndNotRegistered() {
        MatchModeProvider bad = provider("bad_probe", Set.of("demo"),
                TestComponent.requiring(hookLog, Unresolved.class));   // 无人提供 → 坏模板

        registry.registerProvider(bad);                                // 不上抛(运行期口径)

        assertThrows(NoSuchElementException.class, () -> registry.require("bad_probe"),
                "坏模板不进注册表(§2.6)");
        assertTrue(hasSevere("bad_probe"), "SEVERE 点名坏模式 id");
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void buildFailureIsSevereSkippedAndNotRegistered() {
        registry.registerProvider(brokenProvider("broken_probe"));     // build 抛 → 同路径

        assertThrows(NoSuchElementException.class, () -> registry.require("broken_probe"));
        assertTrue(hasSevere("broken_probe"), "第三方工厂 build 异常也 SEVERE + 跳过(7.2 步骤 1)");
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void duplicateIdAtRuntimeIsSevereAndIgnored() {
        registry.registerProvider(provider(PROBE, Set.of("first")));
        registry.registerProvider(provider(PROBE, Set.of("second")));  // 后来者:不抛(双口径另一半)

        assertEquals(Set.of("first"), registry.require(PROBE).mapTags(), "忽略后来者,先到者保留");
        assertTrue(hasSevere("重复的模式 id"), "运行期重复 id 走 SEVERE(§7.2 要点 3)");
        assertEquals(1, registry.all().size());
    }

    // ── ④ 摘除:目录摘除 + 受影响会话回退/解散 ────────────────────

    @Test
    void unregisterRemovesCatalogAndRedirectsAffectedSessionToFallback() {
        registry.registerProvider(provider(FALLBACK, Set.of("demo")));  // fallback 先注册(可用性判定)
        MatchModeProvider probe = provider(PROBE, Set.of("demo"));
        registry.registerProvider(probe);

        RecordingService fake = new RecordingService();
        Session affected = new FixedCurrentSession("s-affected", registry.require(PROBE));
        Session unaffected = OfflineAssembly.session("s-unaffected");
        fake.setSessions(List.of(affected, unaffected));
        ProviderServiceListener listener = new ProviderServiceListener(registry, fake,
                configWithFallback(FALLBACK), contributions, logger, List::of, List::of);

        Set<String> removed = listener.removeProvider(probe);

        assertEquals(Set.of(PROBE), removed, "返回被摘 id 集");
        assertFalse(registry.all().stream().anyMatch(mode -> mode.id().equals(PROBE)),
                "目录摘除(modes() 视角同源,① 已验通路)");
        assertEquals(1, fake.switches.size(), "受影响会话恰一次回退(不重复编排)");
        SwitchCall call = fake.switches.get(0);
        assertEquals(affected.id(), call.sessionId(), "只有当前模式 ∈ 被摘集的会话被打扰");
        assertEquals(FALLBACK, call.selection().modeId(), "回退目标 = 配置的 fallback-mode(§14.21)");
        assertTrue(call.selection().force(), "回退带 force(豁免 min-dwell,§3.5)");
        assertNotNull(call.payload(), "Handoff.empty()");
        assertTrue(fake.dissolves.isEmpty(), "fallback 可用时不解散");
        // 未受影响会话零打扰:切换恰一次且 sessionId 命中受影响会话(上两行),解散记录为空(上一行)
    }

    @Test
    void unregisterWithBlankFallbackDissolvesSession() {
        MatchModeProvider probe = provider(PROBE, Set.of("demo"));
        registry.registerProvider(probe);
        RecordingService fake = new RecordingService();
        fake.setSessions(List.of(new FixedCurrentSession("s1", registry.require(PROBE))));
        ProviderServiceListener listener = new ProviderServiceListener(registry, fake,
                ConfigView.fallbackView(), contributions, logger, List::of, List::of);              // R3:默认空 = 无 fallback

        listener.removeProvider(probe);

        assertEquals(1, fake.dissolves.size(), "fallback 空 → 直接二级 dissolve(★v1.3 R3 空值语义)");
        assertEquals(new DissolveCall(new SessionId("s1"), DissolveReason.FALLBACK_DISSOLVE),
                fake.dissolves.get(0));
        assertTrue(fake.switches.isEmpty(), "不发起切换");
    }

    @Test
    void unregisterWithUnregisteredFallbackDissolvesSession() {
        MatchModeProvider probe = provider(PROBE, Set.of("demo"));
        registry.registerProvider(probe);
        RecordingService fake = new RecordingService();
        fake.setSessions(List.of(new FixedCurrentSession("s1", registry.require(PROBE))));
        ProviderServiceListener listener = new ProviderServiceListener(registry, fake,
                configWithFallback("ghost_mode"), contributions, logger, List::of, List::of);       // 指向未注册 id → 不可用

        listener.removeProvider(probe);

        assertEquals(1, fake.dissolves.size());
        assertEquals(DissolveReason.FALLBACK_DISSOLVE, fake.dissolves.get(0).reason());
    }

    @Test
    void rejectedFallbackSwitchWarnsAndDoesNotSpin() {
        MatchModeProvider probe = provider(PROBE, Set.of("demo"));
        registry.registerProvider(probe);
        registry.registerProvider(provider(FALLBACK, Set.of("demo")));
        RecordingService fake = new RecordingService();
        fake.scripted = new SwitchResult.Rejected(SwitchResult.Reason.SWITCH_IN_PROGRESS, "in flight");
        fake.setSessions(List.of(new FixedCurrentSession("s1", registry.require(PROBE))));
        ProviderServiceListener listener = new ProviderServiceListener(registry, fake,
                configWithFallback(FALLBACK), contributions, logger, List::of, List::of);

        listener.removeProvider(probe);

        assertEquals(1, fake.switches.size(), "拒绝后放弃,不在事件栈自旋重试(§14.17)");
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                && String.valueOf(r.getMessage()).contains("SWITCH_IN_PROGRESS")), "WARN 观测拒绝");
    }

    @Test
    void constructorIngestsPreRegisteredProviders() {
        // 先启场景补收录(§13.4 启动顺序表「TheirPlugin 先启」行):构造期 loadAll 路径
        MatchModeProvider probe = provider(PROBE, Set.of("demo"));
        new ProviderServiceListener(registry, new RecordingService(),
                ConfigView.fallbackView(), contributions, logger, () -> List.of(probe), List::of);

        assertEquals(PROBE, registry.require(PROBE).id(), "构造期初始收录走同一注册闸");
    }

    // ── ④c D14 通道一:先启补收录与摘除半边同键(14.38 幽灵贡献守卫) ──

    @Test
    void preRegisteredContributionsRemovedByProviderPluginKey() {
        // 先启收录键必须 = 注册插件(生产 = RegisteredServiceProvider#getPlugin),与
        // ServiceUnregisterEvent/PluginDisableEvent 摘除半边同键(D14/14.38)——
        // 否则提供方 disable 后贡献永不摘除(幽灵贡献,重注册撞 id 被拒)。
        SessionComponent.Specification<?> spec = new SessionProbeComponent.Spec(new ArrayList<>());
        Object pluginKey = new Object();            // 离线身份桩(零 Bukkit;生产 = Plugin 实例)
        ProviderServiceListener listener = new ProviderServiceListener(registry, new RecordingService(),
                ConfigView.fallbackView(), contributions, logger, List::of,
                () -> List.of(new ProviderServiceListener.KeyedComponentProvider(pluginKey,
                        () -> List.<SessionComponent.Specification<?>>of(spec))));

        assertFalse(contributions.isEmpty(), "前置:构造期已收录先启提供方贡献");
        listener.removeContributions(pluginKey);    // 摘除半边同键(ServiceUnregister/PluginDisable 兜底)
        assertTrue(contributions.isEmpty(), "按注册插件键摘除先启贡献(D14/14.38)");
    }

    @Test
    void reRegistrationReplacesPreRegisteredContributionsWithoutGhostCollision() {
        // 先启收录 → 提供方重注册(同键)→ 干跑合并不得含旧条目(否则同组件 id 撞车
        // → 新贡献被 SEVERE 拒收录,14.38 幽灵贡献的次生面)。
        SessionComponent.Specification<?> first = new SessionProbeComponent.Spec(new ArrayList<>());
        SessionComponent.Specification<?> second = new SessionProbeComponent.Spec(new ArrayList<>());
        Object pluginKey = new Object();
        ProviderServiceListener listener = new ProviderServiceListener(registry, new RecordingService(),
                ConfigView.fallbackView(), contributions, logger, List::of,
                () -> List.of(new ProviderServiceListener.KeyedComponentProvider(pluginKey,
                        () -> List.<SessionComponent.Specification<?>>of(first))));

        listener.ingestContributions(pluginKey,
                () -> List.<SessionComponent.Specification<?>>of(second));   // 同键重注册(等价 ServiceRegisterEvent)

        assertFalse(logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE),
                "同键重注册不撞旧条目(干跑合并干净)");
        assertEquals(List.of(second), contributions.merged(), "合并视图 = 新贡献(旧条目被同键摘除)");
    }

    // ── ④b ★41 增款(★v1.1-D7;7.5 施工文档步骤 3/12):版本协商打点 ──

    @Test
    void requiredApiVersionDefaultsToOne() {
        // 默认方法 = 既有实现零迁移(D7:只增默认方法,不破既有实现)
        assertEquals(1, provider(PROBE, Set.of("demo")).requiredApiVersion(),
                "MatchModeProvider#requiredApiVersion 默认 1(不消费任何版本增量)");
    }

    @Test
    void ingestLogsDeclaredRequiredApiVersion() {
        MatchModeProvider demanding = new MatchModeProvider() {
            @Override
            public String id() {
                return PROBE;
            }

            @Override
            public MatchMode build(MatchModeConfig cfg) {
                return new MatchMode.Builder(id())
                        .mapTags(Set.of("demo"))
                        .meta(new MatchModeMeta(Set.of(), false, 1, 16,
                                MatchModeMeta.DefaultTransition.STAY))
                        .build();
            }

            @Override
            public int requiredApiVersion() {
                return 3;                       // 声明要求 v3 面(地图注册等)
            }
        };
        ProviderServiceListener listener = new ProviderServiceListener(registry, new RecordingService(),
                ConfigView.fallbackView(), contributions, logger, List::of, List::of);

        listener.ingestProvider(demanding);

        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.INFO
                        && formatted(r).contains("requiredApiVersion=3")),
                "收录点对每个 Provider 记录其声明要求(D7/14.36 注册处打点)");
        assertEquals(PROBE, registry.require(PROBE).id(), "声明高版本不拒收录(收录照常,打点供诊断)");
    }

    // ── ⑤ 主仓生产源零第三方示例字面量 ───────────────────────────

    @Test
    void mainSourcesContainNoThirdPartyModeLiteral() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("their_mode")) {
                    violations.add(file.toString());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "主仓生产源不得出现 their_mode 字面量(§14.13;示例只住 examples/TheirPlugin): "
                        + violations);
    }
}
