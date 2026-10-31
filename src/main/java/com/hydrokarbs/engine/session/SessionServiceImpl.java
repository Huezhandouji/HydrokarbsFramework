package com.hydrokarbs.engine.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.World;

import com.hydrokarbs.engine.EngineTicker;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.JoinAdmission;
import com.hydrokarbs.engine.port.JoinContext;
import com.hydrokarbs.engine.port.JoinDecision;
import com.hydrokarbs.engine.port.MatchModeInstanceAccess;
import com.hydrokarbs.engine.port.ModeLookup;
import com.hydrokarbs.engine.port.SessionEventPort;
import com.hydrokarbs.engine.port.SessionPort;
import com.hydrokarbs.engine.port.SessionServices;
import com.hydrokarbs.engine.port.WorldHandle;
import com.hydrokarbs.engine.port.WorldPort;
import com.hydrokarbs.map.MapDefinition;
import com.hydrokarbs.map.MapRegistry;

/**
 * 会话服务实现:两阶段切换 + 三级回退 + 成员进出与解散(03-会话与模式切换 §3.2;
 * 施工计划 2.3 主体)。
 *
 * <p><b>四块结构</b>:
 * <ol>
 *   <li><b>同步段</b>(主线程,内存只读检查):守卫(重入/解散)+ A1~A5 + 防护①
 *       (最短驻留)——对旧实例零副作用,任何失败旧实例继续;唯一耗时步骤 A6
 *       (世界准备)异步,失败也只是「这次切不成」;</li>
 *   <li><b>异步段</b>(A6):{@code worldPort.acquire(...).orTimeout(30s)}(防护④);
 *       成功 → 挂起执行计划、置 {@code SWITCHING}、发切换开始事件;失败/超时 →
 *       future 以拒绝完结(完成回程经注入的主线程执行器,绝不在异步线程,14.1);</li>
 *   <li><b>执行段 B</b>(唯一入口:节拍器经 {@code Session#runPendingSwitch} 下一 tick
 *       驱动):前置人数重校验(防护③)→ B1 交接出局 → B2 幂等清场 → B3 合并
 *       (外部载荷覆盖旧实例自报)→ B4 造新实例两段生效(名册原地位移经入场裁决
 *       触发点①)→ B5 落位/记切换时刻/发完成事件/future 成功/旧世界句柄释放接缝;</li>
 *   <li><b>回退与生死</b>:B 失败两级判据(装配/唤醒抛 = 直接回退;生效抛 = 先全停
 *       再回退)→ 一级递归切换回退模式(配置注入的 id,源码零字面量,带豁免标记;
 *       ★v1.13/31 号:回退无效(空串/未注册)或回退切换被拒 → SEVERE + 即时二级
 *       解散,回退链单点收敛在 fallbackSwitch;默认配置下出厂空模式兜底,回退必
 *       有其落点)→ 二级解散(全员传送主城出生点 + 兜底清态,绝不出现「会话有
 *       成员而无实例」)→ 三级传送失败保持原地 + 通用安全态清理兜底。原请求以
 *       拒绝完结,最终去向经会话事件告知。</li>
 * </ol>
 *
 * <p><b>引擎不直碰平台</b>:成员传送与通用安全态清理经构造注入的函数落在 L0
 * (设计未细化,最小自洽:主城出生点 = 服务器主世界出生点,由装配方提供);
 * 主线程回程经注入的执行器(生产行 = Threads 回程口,离线测试直通)。
 * ★v1.9(27 号):世界清场两口同落本注入模式——切换清场 {@code worldClearer}
 * (旧世界遗留人员 → 目标世界出生点;B3.5 同世界先行 / B5 异图殿后)与解散清场
 * {@code hubWorldClearer}(地图世界遗留人员 → 主世界出生点;dissolveNow finally,
 * 与 hubRelocator 组成「先名册后世界」);全部 best-effort WARN,失败不阻塞
 * 切换/解散,卸载失败保留目录(23 号)仍为最后防线。
 *
 * <p><b>★v4(2.6)接线扩展——公开方法签名不变</b>:① {@code create} 内装配会话级
 * 组件(先于首个实例就绪,§3.8);② {@code dissolve} 内逆序 stop + 登记资源回收;
 * ③ {@code SessionPortImpl} 承载会话自面(两个行动口与 switchMode/dissolve 同一实现,
 * D8)。A/B 两阶段与装配代码零改动(★33)。
 *
 * <p><b>★v1.7(25 号修正案):A2 能力校验随能力标签机制全链退场</b>——原
 * 「声明标签 ⊆ 进程级 ∪ 本会话标签」的字符串校验删除;模式对外部能力的硬依赖改由
 * 会话组件桥接表达:桥接会话组件经 {@code registerSessionComponents}/recipe 进会话,
 * 消费方组件 {@code requires/requiresOptional} 类型化声明,装配期响亮失败、
 * 运行期隔离(quarantine)——机制在框架内既有,本变更不新增代码。
 *
 * <p>线程契约:全部公共方法主线程调用;future 的完成点收敛在节拍器执行段(成功)
 * 或主线程执行器(拒绝)。
 */
public final class SessionServiceImpl implements SessionService {

    /** 世界准备超时(秒,防护④,§3.5)。 */
    private static final long WORLD_PREPARE_TIMEOUT_SECONDS = 30L;

    private final ModeLookup modes;
    private final WorldPort worlds;
    private final MapRegistry maps;
    private final Function<String, MatchModeConfig> configSource;
    private final SessionPolicy policy;
    private final String fallbackModeId;
    private final EngineServices engineServices;
    private final Executor mainExecutor;
    private final Consumer<Collection<UUID>> hubRelocator;
    private final Consumer<Collection<UUID>> safeStateCleaner;
    private final BiConsumer<World, World> worldClearer;      // ★v1.9(27 号):切换世界清场(旧世界 → 目标世界出生点)
    private final Consumer<World> hubWorldClearer;            // ★v1.9(27 号):解散世界清场(地图世界 → 主世界出生点)
    private final Consumer<SessionEvent> sessionEventMirror;
    private final Logger log;

    /** 存活会话表(id → 会话;增删只在 create/dissolve,主线程单线程访问)。 */
    private final Map<SessionId, Session> sessions = new LinkedHashMap<>();

    /** 会话驱动钩子的实现体(全部会话共享同一实例,无会话态)。 */
    private final SessionDrive drive = new SessionDrive();

    private SessionServiceImpl(Builder builder) {
        this.modes = builder.modes;
        this.worlds = builder.worlds;
        this.maps = builder.maps;
        this.configSource = builder.configSource;
        this.policy = builder.policy;
        this.fallbackModeId = builder.fallbackModeId;
        this.engineServices = builder.engineServices;
        this.mainExecutor = builder.mainExecutor;
        this.hubRelocator = builder.hubRelocator;
        this.safeStateCleaner = builder.safeStateCleaner;
        this.worldClearer = builder.worldClearer;
        this.hubWorldClearer = builder.hubWorldClearer;
        this.sessionEventMirror = builder.sessionEventMirror;
        this.log = builder.logger;
    }

    // ── SessionService:创建与查询(★v4:会话组件装配接线,公开方法签名不变) ───

    /**
     * 创建会话并装配会话级组件(★v4/§3.8;顺序是规格的一部分):
     * 建 Session 骨架 → 注册表 bind(逐 Spec {@code freeze()} 快照,id 去重)→
     * {@code verifyDependencies()}(缺会话级必需依赖 → 抛,<b>会话创建失败</b>,
     * L0 SEVERE;arena 则该竞技场不启动)→ 按声明序 {@code create(id, svc)}
     * (构造期注入 svc,四条件同实例级)→ 事件钩子桥接 → {@code awakeAll} →
     * {@code startAll} → 发 {@code SessionCreated} → 走首次 {@code switchMode}(初始模式)。
     * <b>会话组件先于首个实例就绪</b>——首实例的组件才查得到它们。
     *
     * <p>装配期任何一步抛(依赖校验/创建/awake/start)→ 会话创建失败(响亮):
     * 记 SEVERE 后原样上抛,会话不入存活表(与运行期隔离语义不可混淆——
     * 会话未就绪,坏组件不得带着起跑;两种失败语义对照见 2.6「关键实现要点」)。
     */
    @Override
    public Session create(SessionSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (sessions.containsKey(spec.id())) {
            throw new IllegalStateException("重复的会话 id: " + spec.id().value());
        }
        Session session = new Session(spec, policy);
        if (sessionEventMirror != null) {
            // ★v1.1-D3 接线(7.5 施工决策 12):构造后立即挂全局汇聚镜像——先于一切
            // 发布(SessionCreated 亦入汇聚面);镜像异常隔离在总线侧(红线)。
            session.events().setMirror(sessionEventMirror);
        }
        try {
            assembleSessionComponents(session, spec);
        } catch (Throwable t) {
            // 会话创建失败(响亮):已造组件未 start(无玩家痕迹),会话对象整体丢弃,不入表
            log.log(Level.SEVERE, "会话 " + spec.id().value() + " 组件装配失败,会话创建失败(异常上抛)", t);
            throw t;
        }
        session.bindDrive(drive);
        sessions.put(spec.id(), session);
        session.events().publish(SessionEvent.SessionCreated.of(spec.id(), spec.initialModeId()));
        log.log(Level.INFO, "已创建会话 {0}(拓扑 {1},初始模式 {2},会话组件 {3} 个,共 {4} 个存活会话)",
                new Object[] { spec.id().value(), policy.topology(), spec.initialModeId(),
                        spec.sessionComponents().size(), sessions.size() });
        // 首次切换装配初始模式(首切不受最短驻留限制)。为空或未注册 → WARN + 泊回退
        // (★v1.13/31 号:不再「无实例驻留」;回退亦失败即解散)。泊回退走完整 B5 落位
        // 会记切换时刻——模式晚注册时服主可后补切换,但后补非 force 请求在最短驻留窗口
        // 内被防护①拒(相对旧「WARN+保留从未切换、立即可切」是行为回退,31 号登记):
        // 宿主可传 force(MatchModeSelection 在 api 白名单)或等窗口过去。
        // 所有权判据(31 号复审 S1):本回调只接管「同步段被拒/A6 失败」——完结时状态
        // 回 RUNNING 且无挂起计划;B 段失败(rollback 完结 future 时仍在 SWITCHING,
        // 其自身会调 fallbackSwitch)、隔离(onQuarantine 尾部自调)、解散(DISSOLVING)
        // 各有归属,此处重入会造成双重回退:第二发被重入守卫拒 → 误 SEVERE + 强制解散
        // (fallback 明明有效,违需求 1「仅 fallback 无效才解散」)。
        beginSwitch(session, MatchModeSelection.of(spec.initialModeId()), Handoff.empty(), EndResult.nowhere())
                .whenComplete((result, err) -> {
                    if (!(result instanceof SwitchResult.Rejected rejected)) {
                        return;
                    }
                    if (session.state() != SessionState.RUNNING || session.pendingSwitch() != null) {
                        return;             // 已由 rollback/onQuarantine/dissolveNow 接管(所有权判据,见上注)
                    }
                    log.log(Level.WARNING, "会话 {0} 初始模式 {1} 装配失败(为空或未注册;泊回退模式待后补切换): {2}",
                            new Object[] { spec.id().value(), spec.initialModeId(), rejected.detail() });
                    fallbackSwitch(session, EndResult.nowhere());
                });
        return session;
    }

    /**
     * 会话组件装配序(§3.8 装配块逐行):bind 逐 Spec 冻结快照(id 由供给面类型名推导,
     * 注册表内 id 去重——同一命名空间)→ 依赖校验 → 声明序造件(svc 构造期注入)→
     * onEvent 钩子桥接挂总线 → awakeAll → startAll。任一步抛即会话创建失败。
     */
    private void assembleSessionComponents(Session session, SessionSpec spec) {
        var registry = session.sessionComponents();
        for (SessionComponent.Specification<?> componentSpec : spec.sessionComponents()) {
            registry.bind(deriveComponentId(componentSpec), componentSpec.freeze());
        }
        registry.verifyDependencies();
        SessionServices svc = new SessionServices(new SessionPortImpl(session), registry);
        registry.createAll(svc);
        registry.bridgeEventHooks();
        registry.awakeAll();
        registry.startAll();
    }

    /**
     * 会话组件 id 推导(单一落点):供给面类型的简单名——同一会话内同供给面类型的
     * 两个 Spec 会撞 id,由注册表 bind 的 id 去重响亮拒绝(待裁决⑤口径)。
     * id 用作组件 id 与依赖诊断点名;不进快照,跨会话共享 SPEC 无影响。
     */
    private static String deriveComponentId(SessionComponent.Specification<?> spec) {
        return spec.providedType().getSimpleName();
    }

    @Override
    public Optional<Session> sessionOf(UUID player) {
        Objects.requireNonNull(player, "player");
        for (Session session : sessions.values()) {
            if (session.roster().contains(player)) {
                return Optional.of(session);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Session> session(SessionId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public Collection<Session> all() {
        return List.copyOf(sessions.values());
    }

    // ── SessionService:切换(公共入口 = 外部调用,触发来源①) ──────────

    @Override
    public CompletableFuture<SwitchResult> switchMode(SessionId id, MatchModeSelection target, Handoff payload) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(target, "target");
        Session session = sessions.get(id);
        if (session == null) {
            return CompletableFuture.completedFuture(
                    rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE, "会话不存在: " + id.value()));
        }
        return beginSwitch(session, target, payload, EndResult.nowhere());
    }

    // ── 切换核心:同步段(守卫 + A1~A5 + 防护①)与异步段(A6) ──────────

    /**
     * 切换统一内核(外部调用/自动切换/回退递归殊途同归于本方法,§3.2)。
     * 同步段全部为内存只读检查;通过后挂占位计划并异步准备世界。
     */
    private CompletableFuture<SwitchResult> beginSwitch(Session session, MatchModeSelection target,
                                                         Handoff payload, EndResult cause) {
        CompletableFuture<SwitchResult> future = new CompletableFuture<>();
        // 守卫:防护②前半(重入)与解散互斥
        if (session.state() == SessionState.DISSOLVING) {
            future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                    "会话 " + session.id().value() + " 正在解散,拒绝切换"));
            return future;
        }
        if (session.state() == SessionState.SWITCHING || session.pendingSwitch() != null) {
            future.complete(new SwitchResult.Rejected(SwitchResult.Reason.SWITCH_IN_PROGRESS,
                    "会话 " + session.id().value() + " 切换进行中,重入被拒"));
            return future;
        }
        // A1 目标模式在册
        MatchMode mode;
        try {
            mode = modes.require(target.modeId());
        } catch (RuntimeException e) {
            future.complete(new SwitchResult.Rejected(SwitchResult.Reason.MODE_NOT_FOUND,
                    target.modeId() + " 未注册: " + e.getMessage()));
            return future;
        }
        // A2 能力校验已随能力标签机制退场(★v1.7,25 号修正案):模式对外部能力的
        // 硬依赖改由会话组件桥接表达(requires/requiresOptional 类型化声明 + 装配闸
        // + 运行期 quarantine);切换同步段自此无字符串校验步。
        // A3 地图解析(指定地图用之;否则按模式标签挑一张,最小实现取装载序第一张)
        MapDefinition map;
        try {
            map = resolveMap(session, mode, target);
        } catch (PrecheckFailure e) {
            future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE, e.getMessage()));
            return future;
        }
        MatchModeConfig cfg = configSource.apply(mode.id());
        // A3 后半:对全组件表逐个地图适配检查,任何非空 → 拒绝(问题清单进 detail)
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, MatchMode.ComponentEntry> entry : mode.components().entrySet()) {
            List<String> issues = entry.getValue().mapCheck().apply(map, cfg);
            if (issues != null) {
                for (String issue : issues) {
                    problems.add("组件 " + entry.getKey() + ": " + issue);
                }
            }
        }
        if (!problems.isEmpty()) {
            future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                    "地图适配检查未通过(" + map.id() + "): " + problems));
            return future;
        }
        // A4 人数窗(豁免只放开下限;上限不豁免)
        int size = session.roster().size();
        if (size > mode.meta().maxParticipants()
                || (!target.force() && size < mode.meta().minParticipants())) {
            future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                    "人数窗外: 在册 " + size + ",目标窗口 [" + mode.meta().minParticipants()
                            + ", " + mode.meta().maxParticipants() + "]"
                            + (target.force() ? "(豁免下限)" : "")));
            return future;
        }
        // A5 依赖校验(防御性二次:注册闸已验,这里失败即拒绝、旧实例毫发无损)
        try {
            mode.verifyDependencies();
        } catch (RuntimeException e) {
            future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                    "目标模式依赖校验失败: " + e.getMessage()));
            return future;
        }
        // 防护①:最短驻留(非豁免请求一律受限;判据单一落点 dwellRemaining,
        // 与 driveAutoSwitch 的回退预检共用同一公式,★v1.13/31 号提取)
        if (!target.force()) {
            long remaining = dwellRemaining(session);
            if (remaining > 0) {
                log.log(Level.WARNING, "会话 {0} 最短驻留不足,切换被拒: 剩 {1} tick(要求 {2})",
                        new Object[] { session.id().value(), remaining, policy.minDwellTicks() });
                future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                        "驻留不足,剩 " + remaining + " tick(要求 " + policy.minDwellTicks() + ")"));
                return future;
            }
        }
        // A6 异步世界准备:先挂占位计划(阻止切换窗口内的并发请求),就绪后由节拍器执行
        PendingSwitch plan = new PendingSwitch(future, mode, map, cfg,
                payload == null ? Handoff.empty() : payload, target, cause, session.ticks());
        session.setPendingSwitch(plan);
        worlds.acquire(map, session.id())
                .orTimeout(WORLD_PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((handle, err) -> mainExecutor.execute(() -> onWorldReady(session, plan, handle, err)));
        return future;
    }

    /** A6 完成点(主线程执行器内):成功挂起并置 {@code SWITCHING};失败完结 future、旧实例毫发无损。 */
    private void onWorldReady(Session session, PendingSwitch plan, WorldHandle handle, Throwable err) {
        if (plan.aborted || session.pendingSwitch() != plan) {
            // 已被解散作废或被顶替(防御):future 已按作废路径完结;
            // 若获取已成功,副本引用归本计划持有而计划已死——即还引用,防泄漏(§14.19)
            if (handle != null && err == null) {
                worlds.release(handle);
            }
            return;
        }
        if (err != null || handle == null) {
            session.clearPendingSwitch();
            plan.future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                    "世界副本准备失败" + (err != null ? ": " + err : "")));
            return;
        }
        plan.world = handle;
        plan.worldReady = true;
        String fromMode = session.currentInstance().map(MatchModeInstance::modeId).orElse("");
        session.transitionTo(SessionState.SWITCHING);
        session.events().publish(SessionEvent.ModeSwitchStarted.of(session.id(), fromMode, plan.mode.id()));
    }

    /** A3 地图解析:指定地图用之;否则按模式标签 × 会话地图池过滤(模式侧空 mapTags
     *  = 通配任意地图,经 MatchModeRegistry.matchesTags 单一判据,31 号决策 B),空集拒绝;
     *  非空取装载序第一张。 */
    private MapDefinition resolveMap(Session session, MatchMode mode, MatchModeSelection target) {
        if (target.mapId().isPresent()) {
            String mapId = target.mapId().get();
            MapDefinition specified = maps.byId(mapId).orElse(null);
            if (specified == null) {
                throw new PrecheckFailure("指定地图不存在: " + mapId);
            }
            return specified;
        }
        List<String> pool = session.spec().mapPool();
        List<MapDefinition> candidates = new ArrayList<>();
        for (MapDefinition candidate : maps.all()) {
            if (!MatchModeRegistry.matchesTags(mode, candidate)) {
                continue;
            }
            if (!pool.isEmpty() && !pool.contains(candidate.id())) {
                continue;
            }
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            throw new PrecheckFailure("无可用地图: 模式标签 " + mode.mapTags()
                    + (pool.isEmpty() ? "" : ",会话地图池 " + pool));
        }
        return candidates.get(0);       // 最小实现:装载序第一张(自动挑图的占位策略)
    }

    /**
     * 驻余判据(★v1.13/31 号提取,单一落点):距最短驻留还差多少 tick,
     * {@code <= 0} = 驻留已满;从未切换过({@code NO_SWITCH_YET})首切不设限 → 恒 0。
     * beginSwitch 防护①(非豁免请求拒)与 driveAutoSwitch 的回退预检(节流回填)
     * 共用本公式——豁免条件差异(force 标记)在各自调用点,公式不复制。
     */
    private long dwellRemaining(Session session) {
        if (session.lastSwitchTick() == Session.NO_SWITCH_YET) {
            return 0L;
        }
        return Math.max(0L, policy.minDwellTicks() - (session.ticks() - session.lastSwitchTick()));
    }

    // ── 执行段 B(节拍器驱动,主线程单 tick 原子) ────────────────────

    /**
     * B 段执行(唯一入口:节拍器经 {@code runPendingSwitch} → {@link SessionDrive#onDrive})。
     * 挂起计划已冻结全部裁决数据,不再回查注册表(唯一例外:防护③的人数重校验)。
     */
    private void executeB(Session session, PendingSwitch plan) {
        session.clearPendingSwitch();       // 取走执行(此后重入守卫恢复生效)
        String fromMode = session.currentInstance().map(MatchModeInstance::modeId).orElse("");
        // 防护③:准备期人数漂移(非豁免且窗口外 → 中止,旧实例未动)
        int size = session.roster().size();
        MatchModeMeta meta = plan.mode.meta();
        if (size > meta.maxParticipants() || (!plan.forced() && size < meta.minParticipants())) {
            session.transitionTo(SessionState.RUNNING);
            plan.future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                    "执行前人数重校验失败: 在册 " + size + ",窗口 [" + meta.minParticipants()
                            + ", " + meta.maxParticipants() + "]"));
            return;
        }
        try {
            // B1 交接出局(状态尚完整,只准写交接载荷)
            Handoff outgoing = session.currentInstance()
                    .map(MatchModeInstance::dispatchHandoff)
                    .orElseGet(Handoff::empty);
            // B2 幂等清场(唯一清场入口:组件逆序停用 → 登记资源回收 → 清总线)
            session.currentInstance().ifPresent(MatchModeInstance::close);
            // B3 合并:外部载荷覆盖旧实例自报(方向写反 = 外部指令被旧实例劫持)
            Handoff merged = Handoff.merge(outgoing, plan.payload);
            // ★v1.9(27 号)B3.5 世界清场(同世界复用:先行于 B4 组件摆放)。
            // 判据 = org.bukkit.World 引用相等(==,非 instanceName/句柄相等基准):
            // REUSE_DIRTY 同世界归零后再获取经 prepareCopyDir 复载产生【新的】World 对象
            // (引用必不等 → 正确走 B5 异图清场),引用相等只可能来自端口表命中同句柄
            // (同图重开,计数不归零无卸载)。空值分支:current 在 B5 换入前为 null
            // (首切,含每次 create 的首切)→ 跳过本步;sameWorldReuse 一次计算供两处
            // 门闩(此处与 B5 finally)。先行裁决:同世界清场对象 = 世界内全部在场者,
            // 若殿后到 B5 finally 会把 startAll 刚摆好的位次整体拽回出生点——先行则
            // 组件其后摆位,终态正确(同图重开「原地起步→出生点起步」为冻结语义变更,
            // 27 号修正案明文登记;失败不阻塞,B4 照常)。
            WorldHandle current = session.worldHandle();
            boolean sameWorldReuse = current != null && current.world() == plan.world.world();
            if (sameWorldReuse) {
                clearWorldQuietly(current.world(), plan.world.world(), session.id());
            }
            // B4 造新实例并两段生效(名册原地位移经入场裁决触发点①)
            MatchModeInstance next = plan.mode.createInstance(
                    session, plan.map, plan.cfg, plan.world, merged, engineServices);
            next.awakeAll();
            try {
                next.startAll();
            } catch (Throwable startFailure) {
                // 生效段抛 = 部分组件已生效:先全停(幂等清场)再走回退
                next.close();
                throw startFailure;
            }
            // B5 落位(旧世界句柄的释放在切换完成之后,try/finally 接缝)
            WorldHandle previousWorld = session.replaceWorldHandle(plan.world);
            try {
                session.setCurrentInstance(next);
                session.recordSwitchTick(session.ticks());
                session.transitionTo(SessionState.RUNNING);
                session.events().publish(SessionEvent.ModeSwitchCompleted.of(
                        session.id(), fromMode, plan.mode.id()));
                plan.future.complete(new SwitchResult.Succeeded(
                        session.id(), fromMode, plan.mode.id(), plan.cause));
            } finally {
                // 释放旧句柄(§3.7 释放时机:切换完成之后;§14.19 try/finally)。
                // 换图与同图重开都释放旧引用:同图复用时句柄未换(获取已把计数 +1),
                // 计数减一后仍大于零,端口内部零卸载零处置——等价于「无释放动作」。
                // ★v1.9(27 号)B5 世界清场(异图切换:殿后、紧贴释放接缝)——置于既有
                // previousWorld 非 null 守卫【之内】(首切 previousWorld == null,守卫外
                // 即 NPE),release 之前;时序 = 完成事件(ModeSwitchCompleted/future.complete)
                // 之后、release 之前(事件订阅者可见同 tick 内事件派发时遗留人员尚未清)。
                // 门闩 !sameWorldReuse:同图重开已由 B3.5 先行清场,finally 再清会把
                // startAll 刚摆好的位次拽回出生点(恰一次)。清场把旧世界内遗留人员
                // (组件只搬名册成员,此处兜底名册外滞留者)传往目标世界出生点;
                // 失败 WARN 不阻塞,release 照发;世界仍有人时既有 disposeHandle
                // 「unloadWorld false → SEVERE + 保留目录」(23 号)原样为最后防线。
                if (previousWorld != null) {
                    if (!sameWorldReuse) {
                        clearWorldQuietly(previousWorld.world(), plan.world.world(), session.id());
                    }
                    worlds.release(previousWorld);
                }
            }
        } catch (Throwable t) {
            rollback(session, plan, t);
        }
    }

    /** B 段失败回退:原请求以拒绝完结 → 会话回常态 → 递归切换回退模式(一级;
     *  故障自愈立即执行,不加驻留预检——回退目标再失败即二级解散终止,无循环死锁)。 */
    private void rollback(Session session, PendingSwitch plan, Throwable cause) {
        log.log(Level.SEVERE, "切换执行失败,进入回退: 会话 " + session.id().value()
                + " 目标 " + plan.mode.id(), cause);
        plan.future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                "目标模式装配失败: " + cause.getMessage()));
        if (session.state() == SessionState.SWITCHING) {
            session.transitionTo(SessionState.RUNNING);    // 旧实例已清、当前无实例,回常态再递归
        }
        session.setCurrentInstance(null);
        fallbackSwitch(session, EndResult.nowhere());
    }

    /**
     * 切换世界清场口(★v1.9/27 号;平台函数,L0 注入):best-effort——任何失败
     * WARN 不阻塞切换/释放(沿用既有三级兜底语气),由调用方接缝继续原路径。
     */
    private void clearWorldQuietly(World from, World to, SessionId id) {
        try {
            worldClearer.accept(from, to);
        } catch (Throwable t) {
            log.log(Level.WARNING, "切换世界清场失败(不阻塞切换,遗留人员保持原地): 会话 " + id.value(), t);
        }
    }

    /**
     * 解散世界清场口(★v1.9/27 号;平台函数,L0 注入):best-effort——失败 WARN
     * 不阻塞解散,finally 继续 discardSession → 归零卸载(卸载失败保留目录仍为
     * 最后防线,23 号既有路径)。
     */
    private void clearWorldToHubQuietly(World world, SessionId id) {
        try {
            hubWorldClearer.accept(world);
        } catch (Throwable t) {
            log.log(Level.WARNING, "解散世界清场失败(不阻塞解散,遗留人员保持原地): 会话 " + id.value(), t);
        }
    }

    /**
     * 一级回退(★v1.13/31 号收敛):递归切换到配置的回退模式(id 来自构造注入的
     * 配置字符串,源码零字面量;回退是框架内部自愈,带豁免标记绕过人数下限与
     * 最短驻留——服务不变式)。回退亦失败 → 二级解散。
     *
     * <p><b>空值语义(31 号收紧)</b>:回退模式<b>无效(空串或未注册)</b> →
     * <b>SEVERE</b>(原 WARN 跳级)+ 即时二级解散兜底({@code FALLBACK_DISSOLVE});
     * 回退切换被拒/异常 → SEVERE(带 detail)+ 二级解散。出厂空模式经 SPI 注册后,
     * 默认配置下回退必有其落点,空串/未注册只可能是配置错误——正常回退运行不再
     * 与故障同形。{@code cause} 仅承载来源语义(执行失败/摘除回退/首切失败/
     * 无意图结束),不改变判据。
     *
     * <p><b>重入护栏(31 号复审 S1)</b>:解散中、或已有任一切换在途(SWITCHING/
     * 挂起计划,含他路已发起的回退)时本方法为 no-op——同一失败只允许一个回退
     * 归属;不设此护栏时,第二发(如 create 回调与 rollback/onQuarantine 尾部
     * 对同一失败各调一次)会被 beginSwitch 重入守卫拒,误走「回退被拒 → SEVERE +
     * 解散」分支。driveAutoSwitch 的 ENDING 态调用不受影响(ENDING 不在护栏集)。
     */
    private void fallbackSwitch(Session session, EndResult cause) {
        if (session.state() == SessionState.DISSOLVING
                || session.state() == SessionState.SWITCHING
                || session.pendingSwitch() != null) {
            return;                     // 解散进行中/另有切换在途(含他路发起的回退):不重入(护栏见方法注)
        }
        if (fallbackModeId.isBlank()) {
            log.log(Level.SEVERE, "会话 {0} 回退模式未配置(无效回退): 直接二级解散兜底(绝不驻留无实例中间态)",
                    session.id().value());
            dissolveNow(session, DissolveReason.FALLBACK_DISSOLVE);
            return;
        }
        CompletableFuture<SwitchResult> fallback = beginSwitch(session,
                new MatchModeSelection(fallbackModeId, Optional.empty(), true),
                Handoff.empty(), cause);
        fallback.whenComplete((result, err) -> {
            if (err != null || result instanceof SwitchResult.Rejected) {
                if (session.state() == SessionState.DISSOLVING) {
                    return;             // future 因解散作废被拒(非回退目标问题):不告警、不重入
                }
                log.log(Level.SEVERE, "会话 {0} 回退切换被拒/异常,二级解散兜底: {1}",
                        new Object[] { session.id().value(),
                                err != null ? err : ((SwitchResult.Rejected) result).detail() });
                dissolveNow(session, DissolveReason.FALLBACK_DISSOLVE);
            }
        });
    }

    // ── 自动切换(触发来源②③:节拍器在 ENDING 后的下一 tick 驱动) ─────────

    /**
     * ENDING 处置:读结束请求的显式意图;无显式去向回落配置的回退模式
     * (★v1.13/31 号:缺省去向管线退场,读模式元数据的旧口径删除)。回退链三步:
     * 空串先行即散(直呼 fallbackSwitch,其内 SEVERE + dissolveNow——即时解散
     * 不等驻留,消灭空串下每拍 A1 拒绝的无限空转)→ 驻留不足则回填重试
     * (瞬态节流,铁律 6:防「结束→切回退→再结束」风暴——自定义回退模式依赖
     * 无意图结束自持时每窗口至多一轮)→ 驻留已满则带原因切回退(force 豁免
     * 人数下限,服务不变式)。
     * ★ 机械闸门:调用栈必经节拍器(防护②后半),否则拒绝发起并保留请求待下一拍。
     */
    private void driveAutoSwitch(Session session) {
        if (!drivenByTicker()) {
            log.log(Level.WARNING, "会话 {0} 的自动切换未经节拍器驱动,拒绝发起(事件栈内不发切换)",
                    session.id().value());
            return;                     // 结束请求保留,待节拍器正常驱动
        }
        List<Session.EndNotice> notices = session.drainEndNotices();
        if (notices.isEmpty()) {
            return;
        }
        Session.EndNotice notice = notices.get(notices.size() - 1);    // 以最后一条为准
        EndResult result = notice.result();
        if (result.transition().isPresent()) {
            TransitionIntent intent = result.transition().get();
            beginSwitch(session, intent.target(), intent.payload(), result).whenComplete((outcome, err) -> {
                // 同步段被拒(如最短驻留):回填结束请求,下一拍重试直至防护放行
                if (err == null && outcome instanceof SwitchResult.Rejected
                        && session.state() == SessionState.ENDING) {
                    session.restoreEndNotices(notices);
                }
            });
            return;
        }
        // 无显式去向:空串先行即散 + 驻留预检 + 回退(三步,31 号)
        if (fallbackModeId.isBlank()) {
            fallbackSwitch(session, result);
            return;
        }
        if (dwellRemaining(session) > 0) {
            session.restoreEndNotices(notices);        // 驻留窗内回填重试(节流,防回退环风暴)
            return;
        }
        fallbackSwitch(session, result);               // 驻留已满:force 切回退(cause = 本条结束结果)
    }

    /** 自动切换的调用栈检查:栈帧必含节拍器类(测试冻结的机械面,§3.5 防护②)。 */
    private static boolean drivenByTicker() {
        return StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE))
                .walk(frames -> frames.anyMatch(frame -> frame.getDeclaringClass() == EngineTicker.class));
    }

    // ── 成员进出 ────────────────────────────────────────────────

    @Override
    public JoinResult join(SessionId id, UUID player) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(player, "player");
        Session session = sessions.get(id);
        if (session == null) {
            return new JoinResult.Rejected("session-not-found", "会话不存在: " + id.value());
        }
        if (session.state() == SessionState.DISSOLVING) {
            return new JoinResult.Rejected("dissolving", "会话 " + id.value() + " 正在解散,拒绝加入");
        }
        MatchModeInstance current = session.currentInstance().orElse(null);
        if (current == null || session.state() != SessionState.RUNNING) {
            // 无实例或切换中:名册照常登记,入场裁决推迟到新实例装配的触发点①(§3.2.1)
            registerMember(session, player);
            return new JoinResult.Joined(session.id());
        }
        // 触发点②:按组件声明序取第一个裁决实现者;无实现者默认 ADMIT
        JoinAdmission gate = current.joinGate();
        JoinDecision decision = gate != null
                ? gate.admit(player, new JoinContext(JoinContext.Trigger.MID_JOIN, session.id()))
                : new JoinDecision(JoinDecision.Kind.ADMIT, null);
        if (decision.kind() == JoinDecision.Kind.REJECT) {
            return new JoinResult.Rejected("rejected-by-mode",
                    decision.reason() != null ? decision.reason() : "实例入场裁决拒绝");
        }
        registerMember(session, player);
        current.admitParticipant(player);      // 安置细节归组件层
        return new JoinResult.Joined(session.id());
    }

    /** 名册登记(幂等)并发成员加入事件(仅新登记时发)。 */
    private void registerMember(Session session, UUID player) {
        boolean fresh = !session.roster().contains(player);
        session.roster().join(player);
        if (fresh) {
            session.events().publish(SessionEvent.MemberJoined.of(session.id(), player));
        }
    }

    @Override
    public void leave(UUID player, LeaveReason reason) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        Session session = sessionOf(player).orElse(null);
        if (session == null || !session.roster().contains(player)) {
            return;                     // 未入会话/已不在册:no-op
        }
        switch (reason) {
            case QUIT -> {              // 断线:宽限标记(不移除)+ 即发离开事件
                long now = System.currentTimeMillis();
                session.tracker().onDisconnect(player, now);
                session.roster().markDisconnected(player, now);
            }
            case KICK -> session.roster().remove(player);   // 被踢:立即移除(宽限仅服务连接意外中断)
        }
        session.events().publish(SessionEvent.MemberLeft.of(session.id(), player, reason));
    }

    // ── 解散 ────────────────────────────────────────────────────

    @Override
    public void dissolve(SessionId id, DissolveReason reason) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reason, "reason");
        Session session = sessions.get(id);
        if (session == null) {
            log.log(Level.FINE, "解散目标会话不存在(幂等忽略): {0}", id.value());
            return;
        }
        dissolveNow(session, reason);
    }

    /**
     * 解散执行(幂等;★v4/§3.8 顺序是规格的一部分):作废挂起切换 → 置 {@code DISSOLVING}
     * →(若有实例)关闭实例 → <b>会话组件逆序 stop + 登记资源统一回收</b>(★新增)→
     * 成员传送主城出生点(三级:失败保持原地)→ 销毁世界副本接缝(discardSession:
     * 复用窗口随会话解散关闭)→ 兜底清态 → 摘除 →
     * 发解散事件。
     */
    private void dissolveNow(Session session, DissolveReason reason) {
        if (session.state() == SessionState.DISSOLVING) {
            return;                     // 幂等:解散中重复调用直接返回
        }
        // ★v1.13/31 号顺序调整:先置 DISSOLVING 再作废挂起切换——作废会同步完结
        // future,其 whenComplete 回程(create 首切失败接回退、回退被拒接二级解散)
        // 必须已能观察到「解散进行中」:否则回退链在解散内部重入(嵌套解散 +
        // 外层 DISSOLVING 自迁移抛异常 + 重复安置/事件);DISSOLVING 护栏在
        // fallbackSwitch 两处吸收重入。
        session.transitionTo(SessionState.DISSOLVING);
        abortPending(session, "会话解散,挂起切换作废");
        List<UUID> members = session.roster().all().stream().map(SessionMember::uuid).toList();
        try {
            try {
                session.currentInstance().ifPresent(MatchModeInstance::close);
            } catch (Throwable t) {
                log.log(Level.WARNING, "解散时关闭实例异常(继续解散): " + session.id().value(), t);
            }
            session.setCurrentInstance(null);
            // ★v4:会话组件逆序 stop(无条件调用,无单例豁免;已隔离组件已停不重复停)
            // → 登记资源统一回收(订阅/延时任务句柄;stop 返回后框架回收,组件不自注销)
            try {
                session.sessionComponents().stopAll();
                session.sessionComponents().reclaimRegisteredResources();
            } catch (Throwable t) {
                log.log(Level.WARNING, "解散时回收会话组件异常(继续解散): " + session.id().value(), t);
            }
            try {
                hubRelocator.accept(members);       // 成员传送主城出生点(平台函数,L0 注入)
            } catch (Throwable t) {
                log.log(Level.WARNING, "解散传送失败,成员保持原地: 会话 " + session.id().value(), t);
            }
        } finally {
            // 世界副本销毁兜底(§3.7 释放时机行:dissolve 的 finally;§14.19)。
            // 先摘再释放的双保险纪律保留:heldWorld 先摘除,计数表条目与全部副本
            // (含曾发放已保留的目录、迟到 in-flight 的已死短码判据)由
            // discardSession 在端口内部消化——复用窗口随会话解散关闭。
            // 无 heldWorld 也照发:解散时若有在途获取,须先记已死短码防其残留。
            WorldHandle heldWorld = session.worldHandle();
            if (heldWorld != null) {
                session.replaceWorldHandle(null);
            }
            // ★v1.9(27 号)解散世界清场(先名册后世界):名册成员已由上方
            // hubRelocator 安置(try 内),此处只兜底世界内剩余的遗留人员——遗留人员
            // = 物理位于该世界内的全部在线玩家(getPlayers()),不以会话名册为界,
            // 名册外滞留者正是既有清场失败的主因;hubRelocator 抛(WARN 路径)时本口
            // 接住仍在地图世界内的成员。目标 = 主世界出生点(与 hubRelocator 共用
            // L0 的 hubSpawn() 单一来源)。heldWorld 为 null(如初始切换失败即解散)
            // 则跳过、无害。失败 WARN 不阻塞,finally 继续 discardSession → 归零卸载。
            if (heldWorld != null) {
                clearWorldToHubQuietly(heldWorld.world(), session.id());
            }
            worlds.discardSession(session.id());
            try {
                safeStateCleaner.accept(members);   // 兜底清理通用安全态(隐身/模式等)
            } catch (Throwable t) {
                log.log(Level.WARNING, "解散兜底清态异常(忽略): " + session.id().value(), t);
            }
            sessions.remove(session.id());
            session.events().publish(SessionEvent.SessionDissolved.of(session.id(), reason));
            if (reason == DissolveReason.FALLBACK_DISSOLVE) {
                log.log(Level.SEVERE, "会话 {0} 回退模式亦装配失败,已解散兜底(绝不驻留无实例中间态)",
                        session.id().value());
            } else {
                log.log(Level.INFO, "会话 {0} 已解散(原因 {1},成员 {2} 人已安置)",
                        new Object[] { session.id().value(), reason, members.size() });
            }
        }
    }

    /** 作废挂起切换计划(future 以拒绝完结;解散/竞态时调用)。 */
    private void abortPending(Session session, String why) {
        PendingSwitch plan = session.pendingSwitch();
        if (plan == null) {
            return;
        }
        plan.aborted = true;
        session.clearPendingSwitch();
        plan.future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE, why));
    }

    private static SwitchResult.Rejected rejected(SwitchResult.Reason code, String detail) {
        return new SwitchResult.Rejected(code, detail);
    }

    /** 同步段预检失败(内部信号:消息即人读 detail)。 */
    private static final class PrecheckFailure extends RuntimeException {
        PrecheckFailure(String message) {
            super(message);
        }
    }

    // ── ★v4 SessionPort 实现体(会话自面:会话级组件对内核的唯一入口) ────────

    /**
     * 会话自面({@link SessionPort})的实现体,每会话一份(装配期造 svc 时随建)。
     * 读口现取(名册/tick/当前实例);两个行动口(D8)镜像实例级:
     * {@code requestSwitch} 与 {@code switchMode} <b>同一实现</b>(受最短驻留与重入约束,
     * §3.5);{@code requestDissolve} 幂等。仅会话级组件与内核可调——svc 不给其他方。
     */
    private final class SessionPortImpl implements SessionPort {

        private final Session session;

        SessionPortImpl(Session session) {
            this.session = Objects.requireNonNull(session, "session");
        }

        @Override
        public SessionId id() {
            return session.id();
        }

        @Override
        public List<SessionMember> members() {
            return session.roster().all();
        }

        @Override
        public long tick() {
            return session.ticks();
        }

        @Override
        public SessionEventPort events() {
            return session.eventsPort();
        }

        @Override
        public Optional<MatchModeInstanceAccess> currentInstance() {
            // ★ 现取不缓存:empty 覆盖「无实例」与 ENDING/SWITCHING 间隙两种合法情况,
            // 不回落到旧实例(14.27);引用由调用方当场消费,不得越过切换缓存
            if (session.state() != SessionState.RUNNING) {
                return Optional.empty();
            }
            return session.currentInstance().map(MatchModeInstance::access);
        }

        @Override
        public void requestSwitch(MatchModeSelection target, Handoff payload) {
            // 与 switchMode 同一实现(§3.8):统一走 beginSwitch。请求口不带豁免——
            // 载荷选区中的豁免标记被剥离(§3.5:会话组件引发的切换按来源②口径计入
            // 最短驻留,无豁免路径);被拒时 beginSwitch 内已记日志,调用方无返回面
            beginSwitch(session, new MatchModeSelection(target.modeId(), target.mapId(), false),
                    payload == null ? Handoff.empty() : payload, EndResult.nowhere());
        }

        @Override
        public void requestDissolve(DissolveReason reason) {
            Objects.requireNonNull(reason, "reason");
            dissolveNow(session, reason);   // 幂等:解散中重复请求被 DISSOLVING 护栏吸收
        }

        @Override
        public void later(int delayTicks, Runnable task) {
            scheduleSessionLater(session, delayTicks, task);
        }
    }

    /** 会话侧延迟任务(★v4):经平台口调度,句柄登记到发起组件名下(stop 统一回收)。 */
    private void scheduleSessionLater(Session session, int delayTicks, Runnable task) {
        Objects.requireNonNull(task, "task");
        AutoCloseable handle = engineServices.platform().scheduleOnce(delayTicks, task);
        session.sessionComponents()
                .registerResource(session.sessionComponents().callerOnStack(), handle);
    }

    // ── 会话驱动钩子(全部会话共享;挂在每个会话上) ───────────────────

    /**
     * 节拍器驱动落点:①挂起计划世界就绪 → 执行 B 段;②{@code ENDING} → 自动切换处置。
     * 故障隔离:强切回退模式(切换的第三个触发源,§3.1 状态机图「实例 tick 异常」)。
     */
    private final class SessionDrive implements Session.Drive {

        @Override
        public void onDrive(Session session) {
            PendingSwitch plan = session.pendingSwitch();
            if (plan != null) {
                if (plan.aborted) {
                    session.clearPendingSwitch();
                } else if (plan.worldReady) {
                    executeB(session, plan);       // 切换只在此驱动(§16.4-三律①)
                }
                return;                            // 世界准备中:本拍无事可做
            }
            if (session.state() == SessionState.ENDING) {
                driveAutoSwitch(session);
            }
        }

        @Override
        public void onQuarantine(Session session) {
            if (session.state() == SessionState.DISSOLVING) {
                return;
            }
            log.log(Level.SEVERE, "会话驱动异常,隔离并强切回退模式: {0}", session.id().value());
            abortPending(session, "会话故障隔离,挂起切换作废");
            // abortPending 同步完结 future——首切的 create 回调可能在此已发起回退
            // (所有权判据:完结时 RUNNING 且无挂起计划;31 号复审 S1),其挂起计划的
            // SWITCHING 状态不得被下方归一化拽回 RUNNING(否则执行段 B5 的
            // SWITCHING→RUNNING 变自迁移抛异常)。
            try {
                session.currentInstance().ifPresent(MatchModeInstance::close);
            } catch (Throwable t) {
                log.log(Level.WARNING, "隔离时关闭实例异常(继续): " + session.id().value(), t);
            }
            session.setCurrentInstance(null);
            if (session.state() == SessionState.SWITCHING && session.pendingSwitch() == null) {
                session.transitionTo(SessionState.RUNNING);    // 回常态再递归(ENDING 可直达切换;
                                                                // 他路新切换在途则不动,见上注)
            }
            fallbackSwitch(session, EndResult.nowhere());      // 故障自愈立即执行,不加驻留预检;
                                                                // 已有在途回退时为 no-op(重入护栏)
        }
    }

    // ── 构造器(依赖全部注入;离线测试可替换每一跳) ─────────────────────

    /** 装配构造器:必填项缺失在 {@link #build()} 响亮抛。 */
    public static final class Builder {

        private ModeLookup modes;
        private WorldPort worlds;
        private MapRegistry maps;
        private Function<String, MatchModeConfig> configSource;
        private SessionPolicy policy;
        private String fallbackModeId;
        private EngineServices engineServices;
        private Executor mainExecutor = Runnable::run;
        private Consumer<Collection<UUID>> hubRelocator = members -> { };
        private Consumer<Collection<UUID>> safeStateCleaner = members -> { };
        private BiConsumer<World, World> worldClearer = (from, to) -> { };   // ★v1.9(27 号):默认 no-op
        private Consumer<World> hubWorldClearer = world -> { };              // ★v1.9(27 号):默认 no-op
        private Consumer<SessionEvent> sessionEventMirror;
        private Logger logger = Logger.getLogger(SessionServiceImpl.class.getName());

        /** 模式查询口(生产 = 模式注册表;测试 = 可控假实现)。 */
        public Builder modes(ModeLookup modes) {
            this.modes = Objects.requireNonNull(modes, "modes");
            return this;
        }

        /** 世界端口(2.4 前注入占位实现,接口不变)。 */
        public Builder worlds(WorldPort worlds) {
            this.worlds = Objects.requireNonNull(worlds, "worlds");
            return this;
        }

        public Builder maps(MapRegistry maps) {
            this.maps = Objects.requireNonNull(maps, "maps");
            return this;
        }

        /** 模式配置源(与模式注册表同源;生产 = 配置仓库)。 */
        public Builder configSource(Function<String, MatchModeConfig> configSource) {
            this.configSource = Objects.requireNonNull(configSource, "configSource");
            return this;
        }

        /** 会话策略模板(create 按此建会话;从 {@code config.yml} 会话块构造)。 */
        public Builder policy(SessionPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * 回退模式 id(配置注入,引擎零字面量;§14.21)。★v1.13(31 号):默认资源
         * 值 = 出厂空模式 id(经 SPI 注册兜底);空串/未注册 = <b>无效回退</b>——
         * 回退需求时 SEVERE + 强制解散(fallbackSwitch 单点判据);仅 {@code null}
         * 视为装配缺失。
         */
        public Builder fallbackModeId(String fallbackModeId) {
            this.fallbackModeId = Objects.requireNonNull(fallbackModeId, "fallbackModeId");
            return this;
        }

        /** 引擎服务集(造实例的末位参数)。 */
        public Builder engineServices(EngineServices engineServices) {
            this.engineServices = Objects.requireNonNull(engineServices, "engineServices");
            return this;
        }

        /** 主线程执行器(生产行 = Threads 回程口;离线测试默认直通)。 */
        public Builder mainExecutor(Executor mainExecutor) {
            this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
            return this;
        }

        /** 成员传送主城出生点函数(平台侧,Bukkit 触点收敛在 L0 注入)。 */
        public Builder hubRelocator(Consumer<Collection<UUID>> hubRelocator) {
            this.hubRelocator = Objects.requireNonNull(hubRelocator, "hubRelocator");
            return this;
        }

        /** 通用安全态清理函数(三级回退兜底:清模式/隐身等,L0 注入)。 */
        public Builder safeStateCleaner(Consumer<Collection<UUID>> safeStateCleaner) {
            this.safeStateCleaner = Objects.requireNonNull(safeStateCleaner, "safeStateCleaner");
            return this;
        }

        /**
         * 切换世界清场函数(★v1.9/27 号,平台侧,L0 注入):把旧世界内全部在线玩家
         * ({@code World#getPlayers()},遗留人员)传往目标世界出生点
         * ({@code World#getSpawnLocation()})。调用点 = executeB 的 B3.5(同世界复用,
         * 先行于组件摆放)与 B5 finally(异图,殿后于完成事件、release 之前);引擎
         * 侧 best-effort 包裹,本函数抛任何异常只记 WARN 不阻塞切换。默认 no-op
         * (离线测试零 Bukkit 静态触碰)。
         */
        public Builder worldClearer(BiConsumer<World, World> worldClearer) {
            this.worldClearer = Objects.requireNonNull(worldClearer, "worldClearer");
            return this;
        }

        /**
         * 解散世界清场函数(★v1.9/27 号,平台侧,L0 注入):把地图世界内全部在线
         * 玩家(遗留人员)传往主世界出生点(生产 = 与 {@code hubRelocator} 同源的
         * hubSpawn() 单一口径)。调用点 = dissolveNow 的 finally(名册传送之后的
         * 「先名册后世界」接缝,worlds.discardSession 之前);best-effort 同上,
         * 失败不阻塞解散。默认 no-op。
         */
        public Builder hubWorldClearer(Consumer<World> hubWorldClearer) {
            this.hubWorldClearer = Objects.requireNonNull(hubWorldClearer, "hubWorldClearer");
            return this;
        }

        /**
         * 全局汇聚镜像(★v1.1-D3,7.5 施工决策 12;可空 = 不挂镜像):每个新会话
         * 构造后立即 {@code Session.events().setMirror(本消费者)}——会话事件照旧进
         * 本会话总线,同时镜像到全局汇聚总线(L0 ⑨ 号 {@code SessionEventBus});
         * 生产装配传入 {@code sessionEvents::publish}。镜像异常隔离在总线侧,
         * 不影响本会话派发(红线)。
         */
        public Builder sessionEventMirror(Consumer<SessionEvent> sessionEventMirror) {
            this.sessionEventMirror = sessionEventMirror;   // null 合法 = 不挂镜像(离线测试默认)
            return this;
        }

        /** 日志注入(测试捕获断言用)。 */
        public Builder logger(Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
            return this;
        }

        public SessionServiceImpl build() {
            StringBuilder missing = new StringBuilder();
            if (modes == null) missing.append("modes ");
            if (worlds == null) missing.append("worlds ");
            if (maps == null) missing.append("maps ");
            if (configSource == null) missing.append("configSource ");
            if (policy == null) missing.append("policy ");
            if (fallbackModeId == null) missing.append("fallbackModeId ");   // 空串合法(无效回退语义,31 号),仅 null 缺失
            if (engineServices == null) missing.append("engineServices ");
            if (!missing.isEmpty()) {
                throw new IllegalStateException("SessionServiceImpl 依赖缺失: " + missing);
            }
            return new SessionServiceImpl(this);
        }
    }
}
