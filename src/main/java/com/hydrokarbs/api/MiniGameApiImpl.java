package com.hydrokarbs.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.kyori.adventure.text.minimessage.MiniMessage;

import com.hydrokarbs.api.view.MapInfo;
import com.hydrokarbs.api.view.MatchModeInfo;
import com.hydrokarbs.api.view.SessionInfo;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.component.SessionComponentContributionRegistry;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.session.CapabilityRegistry;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionMember;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.map.MapDefinition;
import com.hydrokarbs.map.MapRegistry;

/**
 * {@link MiniGameApi} 实现体:薄委托(07-公共API §7.2 表;7.1 施工文档步骤 6、
 * 7.5 步骤 5)。v1 面方法零业务分支的纪律原样有效;7.5 增量同样是「查表 → 转换 →
 * 委托」——成员操作 → {@code SessionService};能力标签 → {@code CapabilityRegistry}
 * (tag-only 形态不动,提供方归属映射在本类伴生,7.5 决策 5);地图注册 →
 * {@code MapRegistry} 新口(正用守卫在本类,目录类不查会话);订阅 → 全局汇聚总线
 * (D3 镜像接线,7.5 决策 12);recipe 建会话 → 合并序后组 {@code SessionSpec}
 * 委托 {@code SessionService#create}(查重/缺依赖由引擎装配闸响亮拒,D14)。
 *
 * <p><b>依赖面</b>:engine 契约({@code SessionService}/{@code MatchModeRegistry})+
 * 地图读面({@code MapRegistry})+ 能力标签机制件 + 会话事件总线(汇聚面)+
 * 贡献收录表(D14;均在 {@code apiDependsOnlyOnEngine} 允许集内,§16.2/7.5 决策 4)
 * + MiniMessage 序列化(平台文本原语)。主线程执行器构造注入:生产行 =
 * {@code Threads::runOnMain}(装配点传入;离线测试注入直通/单线程假——本类不
 * import {@code util.Threads},api 包不依赖允许集外的框架包)。
 *
 * <p><b>L0 装配面(不在 {@code MiniGameApi} 冻结面)</b>:
 * {@code unregisterCapabilitiesOf}/{@code unregisterMapsOf} 仅供
 * {@code PluginDisableEvent} 兜底监听调用(14.38/D8/7.5 决策 5——提供方插件
 * disable 自动摘除的框架侧落点);公开面消费者不触。
 *
 * <p><b>线程契约</b>(见接口 javadoc 表):快照/成员/能力/地图方法假定主线程;
 * {@code switchMode} 任意线程发起、经 {@code mainExecutor} 回主线程查表与委托,
 * 异常经 future 传播、绝不悬挂(§14.1/§3.2)。订阅回调发生在事件发布线程
 * (会话事件发布在主线程),逐订阅者隔离在总线侧。
 */
public final class MiniGameApiImpl implements MiniGameApi {

    private static final Logger LOG = Logger.getLogger(MiniGameApiImpl.class.getName());

    /** MiniMessage 实例建一次复用(papermc 纪律;序列化只在模式声明文案的转换点)。 */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SessionService sessionService;
    private final MatchModeRegistry modeRegistry;
    private final MapRegistry mapRegistry;
    private final CapabilityRegistry capabilities;
    private final SessionEventBus globalEvents;
    private final SessionComponentContributionRegistry contributions;
    private final Executor mainExecutor;

    /**
     * 能力标签提供方归属映射(★v1.1-D4,7.5 决策 5):提供方身份键 → 其主张的标签集。
     * 提供方插件 disable → 逐标签摘除并补回仍被其他提供方主张的标签(兜底监听消费)。
     * 主线程单线程访问;身份键语义(按实例),7.2 要点 4 同款。
     */
    private final Map<Object, Set<String>> capabilityClaims = new LinkedHashMap<>();

    /**
     * @param sessionService 会话服务(engine 契约;快照/切换/成员/建会话的委托目标,§3.2)
     * @param modeRegistry   模式注册表(目录数据源,§12.1)
     * @param mapRegistry    地图目录(装载序 + 注册序清单;注册/摘除委托目标,★v1.2)
     * @param capabilities   能力标签注册表(tag-only 机制件,D4 的写目标,7.5 决策 5)
     * @param globalEvents   全局汇聚事件总线(⑨ 号;订阅面数据源,D3 镜像接线,7.5 决策 12)
     * @param contributions  会话组件贡献收录表(D14 通道一;recipe 合并序的第一段)
     * @param mainExecutor   主线程回程口(switchMode 发起 hop;生产行 = {@code Threads::runOnMain})
     */
    public MiniGameApiImpl(SessionService sessionService, MatchModeRegistry modeRegistry,
                           MapRegistry mapRegistry, CapabilityRegistry capabilities,
                           SessionEventBus globalEvents,
                           SessionComponentContributionRegistry contributions,
                           Executor mainExecutor) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.modeRegistry = Objects.requireNonNull(modeRegistry, "modeRegistry");
        this.mapRegistry = Objects.requireNonNull(mapRegistry, "mapRegistry");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.globalEvents = Objects.requireNonNull(globalEvents, "globalEvents");
        this.contributions = Objects.requireNonNull(contributions, "contributions");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
    }

    // ── MiniGameApi:查询 ────────────────────────────────────────────

    @Override
    public Optional<SessionInfo> sessionOf(UUID player) {
        return sessionService.sessionOf(player).map(this::snapshot);
    }

    @Override
    public Optional<SessionInfo> session(String sessionId) {
        return sessionService.session(new SessionId(sessionId)).map(this::snapshot);
    }

    @Override
    public List<SessionInfo> sessions() {
        List<SessionInfo> snapshots = new ArrayList<>();
        for (Session session : sessionService.all()) {
            snapshots.add(snapshot(session));
        }
        return List.copyOf(snapshots);
    }

    // ── MiniGameApi:控制 ────────────────────────────────────────────

    @Override
    public CompletableFuture<SwitchResult> switchMode(String sessionId, MatchModeSelection target,
                                                      Handoff payload) {
        Objects.requireNonNull(target, "target");
        CompletableFuture<SwitchResult> future = new CompletableFuture<>();
        // 任意线程发起 → 回主线程查表与发起(§3.2 主线程口径;§14.1)。
        // 任何异常(空白 id 的 SessionId 响亮抛等)经 future 传播——绝不让异常落进
        // 执行器、future 永不悬挂(§3.2)。
        mainExecutor.execute(() -> {
            try {
                Optional<Session> found = sessionService.session(new SessionId(sessionId));
                CompletableFuture<SwitchResult> delegated = found.isEmpty()
                        ? CompletableFuture.completedFuture(new SwitchResult.Rejected(
                                SwitchResult.Reason.MODE_NOT_FOUND, "session " + sessionId + " 不存在"))
                        : sessionService.switchMode(found.get().id(), target, payload);
                delegated.whenComplete((result, err) -> {
                    if (err != null) {
                        future.completeExceptionally(err);
                    } else {
                        future.complete(result);
                    }
                });
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public SessionInfo createSession(String initialModeId, String initialMapId) {
        // 未知模式 → 注册表既有响亮抛(require,§12.1 同口径;边界校验,api-and-interface 纪律)
        modeRegistry.require(initialModeId);
        String mapId = initialMapId == null ? "" : initialMapId.strip();
        List<String> mapPool = mapId.isEmpty() ? List.of() : List.of(mapId);
        return snapshot(sessionService.create(new SessionSpec(generatedId(), initialModeId, mapPool)));
    }

    @Override
    public SessionInfo createSession(SessionRecipe recipe) {
        Objects.requireNonNull(recipe, "recipe");
        // 未知模式 → 注册表既有响亮抛(镜像两参重载口径,§12.1)
        modeRegistry.require(recipe.initialModeId());
        // 合并序(D14;21-D5 后 api 建会话的宿主 spec 组件表恒为空):
        // ①已收录 provider 贡献(注册序)→ ②recipe 单次贡献。
        List<SessionComponent.Specification<?>> components = new ArrayList<>(contributions.merged());
        components.addAll(recipe.extraComponents());
        // 钉图置池首(与两参重载「指定图 = 池单元素」同源口径,首切换自动挑图按池序 §3.2-A3)
        List<String> pool = new ArrayList<>(recipe.mapPool());
        String pinned = recipe.initialMapId().orElse("").strip();
        if (!pinned.isEmpty() && !pool.contains(pinned)) {
            pool.add(0, pinned);
        }
        // 查重/缺依赖响亮拒:引擎装配闸(SessionServiceImpl#create 内 bind 查重 +
        // verifyDependencies,装配失败 = 会话创建失败,原样上抛)——不写第二套校验。
        return snapshot(sessionService.create(
                new SessionSpec(generatedId(), recipe.initialModeId(), pool, components)));
    }

    // ── MiniGameApi:成员操作(D2;SessionService#join/leave 的受控暴露) ──

    @Override
    public JoinResult joinSession(String sessionId, UUID player) {
        // 引擎既有裁决原样透传:会话不存在/解散中/入场拒绝 → Rejected(detail 人读,§3.2)
        return sessionService.join(new SessionId(sessionId), player);
    }

    @Override
    public void leaveSession(UUID player, LeaveReason reason) {
        sessionService.leave(player, reason);       // 不在会话 = no-op(幂等,§3.2 既有口径)
    }

    // ── MiniGameApi:会话事件订阅(D3;全局汇聚总线订阅 + 快照转换) ──────

    @Override
    public EventSubscription subscribeSessionEvents(Consumer<SessionEventView> handler) {
        Objects.requireNonNull(handler, "handler");
        // 汇聚总线订阅:每个会话的事件经镜像点(D3 接线)到达这里,快照转换后回调;
        // 订阅者异常隔离、close 幂等注销均在总线既有语义内(★43)。
        SessionEventBus.Subscription handle = globalEvents.subscribe(SessionEvent.class,
                event -> handler.accept(toEventView(event)));
        return handle::close;
    }

    // ── MiniGameApi:能力标签(D4;机制件 tag-only 形态不动,归属映射伴生) ──

    @Override
    public void registerCapability(org.bukkit.plugin.Plugin provider, String tag) {
        Objects.requireNonNull(provider, "provider");
        capabilities.register(tag);                 // 幂等 + 空白校验(引擎既有;7.5 决策 5)
        capabilityClaims.computeIfAbsent(provider, key -> new LinkedHashSet<>()).add(tag);
    }

    @Override
    public Set<String> capabilities() {
        return capabilities.available();
    }

    // ── MiniGameApi:地图注册(★v1.2;目录委托 + 正用守卫) ────────────────

    @Override
    public MapInfo registerMap(org.bukkit.plugin.Plugin provider, MapDefinition map) {
        Objects.requireNonNull(provider, "provider");
        // 注册期校验(id 查重点名先到者/tags/模板目录存在性)在目录内响亮抛(20-D3)
        return toMapInfo(mapRegistry.register(provider, map));
    }

    @Override
    public void unregisterMap(org.bukkit.plugin.Plugin provider, String mapId) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(mapId, "mapId");
        // 正用守卫(20-D4;7.5 决策 14):任一存活会话当前实例正用该图 → 摘除被拒,
        // detail 点名会话(图随会话打完自然退出,或 dissolve 后再摘)。
        String usedBy = findSessionUsing(mapId);
        if (usedBy != null) {
            throw new IllegalStateException("地图 " + mapId + " 正被会话 " + usedBy
                    + " 使用,摘除被拒(20-D4;未释放世界句柄无公开查询口,按当前实例落最小守卫"
                    + "——7.5 施工决策 14)");
        }
        mapRegistry.unregister(provider, mapId);    // 文件式拒绝/幂等 no-op 在目录侧(20-D4/D5)
    }

    // ── L0 装配面:PluginDisableEvent 兜底摘除(不在 MiniGameApi 冻结面;14.38/D8) ──

    /**
     * 摘除某提供方注册的全部能力标签(D4;兜底监听专用)。多提供方主张同一标签时,
     * 先逐标签摘除、再把仍被其他提供方主张的标签补回(「可用 ⟺ 至少一个提供方主张」
     * 不变式;跨域同标签(服务侧注册在前)由提供方词汇纪律规避,边界登记于 javadoc)。
     */
    public void unregisterCapabilitiesOf(org.bukkit.plugin.Plugin provider) {
        Set<String> claimed = capabilityClaims.remove(provider);
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        for (String tag : claimed) {
            capabilities.unregister(tag);
        }
        for (Set<String> remaining : capabilityClaims.values()) {
            for (String tag : remaining) {
                if (claimed.contains(tag)) {
                    capabilities.register(tag);
                }
            }
        }
        LOG.log(Level.INFO, "已按提供方摘除能力标签(D8 兜底): {0} → {1}",
                new Object[] { provider.getName(), claimed });
    }

    /**
     * 摘除某提供方注册的全部地图(★v1.2;兜底监听专用):逐张走
     * {@link #unregisterMap} 同一守卫——正用拒绝 = 保留并 WARN(摘除被拒是特性,
     * 20-D4),其余摘除。幂等(无注册 → 空集 no-op)。
     */
    public void unregisterMapsOf(org.bukkit.plugin.Plugin provider) {
        for (String mapId : mapRegistry.registeredIdsOf(provider)) {
            try {
                unregisterMap(provider, mapId);
            } catch (RuntimeException refused) {
                LOG.log(Level.WARNING, "兜底摘除注册式地图被拒,保留(会话正用是特性,20-D4): {0} —— {1}",
                        new Object[] { mapId, refused.getMessage() });
            }
        }
    }

    // ── MiniGameApi:目录 ────────────────────────────────────────────

    @Override
    public List<MatchModeInfo> modes() {
        List<MatchModeInfo> snapshots = new ArrayList<>();
        for (MatchMode mode : modeRegistry.all()) {
            snapshots.add(toModeInfo(mode));
        }
        return List.copyOf(snapshots);
    }

    @Override
    public List<MapInfo> maps() {
        List<MapInfo> snapshots = new ArrayList<>();
        for (MapDefinition map : mapRegistry.all()) {
            snapshots.add(toMapInfo(map));
        }
        return List.copyOf(snapshots);
    }

    @Override
    public Optional<MatchModeInfo> mode(String modeId) {
        try {
            return Optional.of(toModeInfo(modeRegistry.require(modeId)));
        } catch (NoSuchElementException missing) {
            return Optional.empty();            // mode() 缺失 → empty(7.1 步骤 6 失败语义列)
        }
    }

    // ── 转换(快照纪律:转换点一次性取值、集合拷贝、不持活引用——7.1 要点 1) ──

    /** 会话 → 快照:无当前实例(含切换间隙)modeId/mapId 空串;members 含宽限期成员。 */
    private SessionInfo snapshot(Session session) {
        Optional<MatchModeInstance> current = session.currentInstance();
        String modeId = current.map(MatchModeInstance::modeId).orElse("");
        String mapId = current.map(instance -> instance.view().map().id()).orElse("");
        List<UUID> members = session.roster().all().stream().map(SessionMember::uuid).toList();
        return new SessionInfo(session.id().value(), modeId, mapId, members,
                session.state().name());
    }

    /** 模式 → 快照:displayName/description 经 MiniMessage 序列化(MINI 静态复用)。 */
    private MatchModeInfo toModeInfo(MatchMode mode) {
        return new MatchModeInfo(mode.id(), MINI.serialize(mode.displayName()),
                mode.description().stream().map(MINI::serialize).toList(), mode.icon(),
                Set.copyOf(mode.mapTags()), Set.copyOf(mode.meta().requiredCapabilities()),
                mode.meta().recordsHistory(), mode.meta().minParticipants(),
                mode.meta().maxParticipants(), mode.meta().defaultTransition().name());
    }

    /** 地图 → 快照(分量与施工文档形状的出入见 MapInfo javadoc)。 */
    private MapInfo toMapInfo(MapDefinition map) {
        return new MapInfo(map.id(), map.displayName(), map.world(), Set.copyOf(map.tags()),
                map.resetStrategy().name());
    }

    /**
     * 引擎会话事件 → api 快照族(D3;七 record 对应 + ★v4 隔离事件)。快照纪律:
     * 转换点一次性取值、枚举存 {@code name()}、不持活引用(07 §7.1 第二代码块;
     * 7.5 决策 13:{@code tick} 承载 {@code occurredAtMillis} 毫秒时间戳)。
     * {@code ModeSwitchCompleted#ok} 恒 true——引擎仅在切换成功(B5 落位)后发布,
     * 语义见 SessionEventView 接口注。
     */
    private static SessionEventView toEventView(SessionEvent event) {
        if (event instanceof SessionEvent.SessionCreated created) {
            return new SessionEventView.Created(created.session(), created.occurredAtMillis());
        }
        if (event instanceof SessionEvent.MemberJoined joined) {
            return new SessionEventView.MemberJoined(joined.session(), joined.member(),
                    joined.occurredAtMillis());
        }
        if (event instanceof SessionEvent.MemberLeft left) {
            return new SessionEventView.MemberLeft(left.session(), left.member(),
                    left.reason().name(), left.occurredAtMillis());
        }
        if (event instanceof SessionEvent.MemberReconnected reconnected) {
            return new SessionEventView.MemberReconnected(reconnected.session(), reconnected.member(),
                    reconnected.occurredAtMillis());
        }
        if (event instanceof SessionEvent.ModeSwitchStarted started) {
            return new SessionEventView.ModeSwitchStarted(started.session(), started.fromMode(),
                    started.toMode(), started.occurredAtMillis());
        }
        if (event instanceof SessionEvent.ModeSwitchCompleted completed) {
            return new SessionEventView.ModeSwitchCompleted(completed.session(), completed.fromMode(),
                    completed.toMode(), true, completed.occurredAtMillis());
        }
        if (event instanceof SessionEvent.SessionDissolved dissolved) {
            return new SessionEventView.Dissolved(dissolved.session(), dissolved.reason().name(),
                    dissolved.occurredAtMillis());
        }
        SessionEvent.SessionComponentQuarantined quarantined =
                (SessionEvent.SessionComponentQuarantined) event;   // 封闭子族末支
        return new SessionEventView.ComponentQuarantined(quarantined.session(),
                quarantined.componentId(), quarantined.reason(), quarantined.occurredAtMillis());
    }

    /** 正用守卫查询:返回正在使用该图的存活会话 id(无则 null;当前实例口径,7.5 决策 14)。 */
    private String findSessionUsing(String mapId) {
        for (Session session : sessionService.all()) {
            String used = session.currentInstance()
                    .map(instance -> instance.view().map().id())
                    .orElse("");
            if (used.equals(mapId)) {
                return session.id().value();
            }
        }
        return null;
    }

    /**
     * api 建会话的 id 生成:框架前缀 + UUID(小写十六进制与连字符,天然满足
     * {@code SessionId#shortCode} 的安全字符集;§3.6 arena 场景,宿主经 sessions() 查询)。
     */
    private static SessionId generatedId() {
        return new SessionId("api-" + UUID.randomUUID());
    }
}
