package com.hydrokarbs.engine.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.EngineTicker;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
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
 *       再回退)→ 一级递归切换回退模式(配置注入的 id,源码零字面量,带豁免标记)→
 *       二级解散(全员传送主城出生点 + 兜底清态,绝不出现「会话有成员而无实例」)→
 *       三级传送失败保持原地 + 通用安全态清理兜底。原请求以拒绝完结,最终去向经
 *       会话事件告知。</li>
 * </ol>
 *
 * <p><b>引擎不直碰平台</b>:成员传送与通用安全态清理经构造注入的函数落在 L0
 * (设计未细化,最小自洽:主城出生点 = 服务器主世界出生点,由装配方提供);
 * 主线程回程经注入的执行器(生产行 = Threads 回程口,离线测试直通)。
 *
 * <p><b>★v4(2.6)接线扩展——公开方法签名不变</b>:① {@code create} 内装配会话级
 * 组件(先于首个实例就绪,§3.8);② {@code dissolve} 内逆序 stop + 登记资源回收;
 * ③ 切换阶段 A2 可用集扩为「进程级标签 ∪ 本会话会话组件标签」(D6)——推荐
 * <b>本会话标签集并集</b>而非进程级 {@code register/unregister}(arena 多会话同标签
 * 不互踩、随 dissolve 天然摘除;备选「进程级注册路径」归宿主扩展线,★ 待裁决②,
 * 见施工计划 2.6「要点」末条);④ {@code SessionPortImpl} 承载会话自面(两个行动口
 * 与 switchMode/dissolve 同一实现,D8)。A/B 两阶段与装配代码零改动(★33)。
 *
 * <p>线程契约:全部公共方法主线程调用;future 的完成点收敛在节拍器执行段(成功)
 * 或主线程执行器(拒绝)。
 */
public final class SessionServiceImpl implements SessionService {

    /** 世界准备超时(秒,防护④,§3.5)。 */
    private static final long WORLD_PREPARE_TIMEOUT_SECONDS = 30L;

    private final ModeLookup modes;
    private final CapabilityRegistry capabilities;
    private final WorldPort worlds;
    private final MapRegistry maps;
    private final Function<String, MatchModeConfig> configSource;
    private final SessionPolicy policy;
    private final String fallbackModeId;
    private final EngineServices engineServices;
    private final Executor mainExecutor;
    private final Consumer<Collection<UUID>> hubRelocator;
    private final Consumer<Collection<UUID>> safeStateCleaner;
    private final Consumer<SessionEvent> sessionEventMirror;
    private final Logger log;

    /** 存活会话表(id → 会话;增删只在 create/dissolve,主线程单线程访问)。 */
    private final Map<SessionId, Session> sessions = new LinkedHashMap<>();

    /** 会话驱动钩子的实现体(全部会话共享同一实例,无会话态)。 */
    private final SessionDrive drive = new SessionDrive();

    private SessionServiceImpl(Builder builder) {
        this.modes = builder.modes;
        this.capabilities = builder.capabilities;
        this.worlds = builder.worlds;
        this.maps = builder.maps;
        this.configSource = builder.configSource;
        this.policy = builder.policy;
        this.fallbackModeId = builder.fallbackModeId;
        this.engineServices = builder.engineServices;
        this.mainExecutor = builder.mainExecutor;
        this.hubRelocator = builder.hubRelocator;
        this.safeStateCleaner = builder.safeStateCleaner;
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
        // 首次切换装配初始模式(首切不受最短驻留限制)。为空或未注册 → WARN + 会话保留
        // 待后补切换——模式晚注册时服主可后补,不炸启动;引擎对空串照走 A1 未注册拒绝,零特判。
        beginSwitch(session, MatchModeSelection.of(spec.initialModeId()), Handoff.empty(), EndResult.nowhere())
                .whenComplete((result, err) -> {
                    if (result instanceof SwitchResult.Rejected rejected) {
                        log.log(Level.WARNING, "会话 {0} 初始模式 {1} 装配失败(为空或未注册;会话保留待后补切换): {2}",
                                new Object[] { spec.id().value(), spec.initialModeId(), rejected.detail() });
                    }
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
     * id 用作组件命名空间(attrs)与依赖诊断点名;不进快照,跨会话共享 SPEC 无影响。
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
        // A2 能力校验(★v4-D6):声明标签 ⊆ 可用标签——可用集 = 进程级标签 ∪ 本会话
        // 会话组件标签(随 dissolve 摘除;推荐「本会话标签集并集」而非进程级
        // register/unregister——arena 多会话同标签不互踩;★ 待裁决②,见类注)
        Set<String> missing = new LinkedHashSet<>(mode.meta().requiredCapabilities());
        missing.removeAll(capabilities.available());
        missing.removeAll(session.sessionComponentCapabilities());
        if (!missing.isEmpty()) {
            future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                    "目标模式声明的能力标签缺失: " + missing));
            return future;
        }
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
        // 防护①:最短驻留(非豁免请求一律受限;从未切换过的会话首切不设限)
        if (!target.force() && session.lastSwitchTick() != Session.NO_SWITCH_YET) {
            long dwell = session.ticks() - session.lastSwitchTick();
            long required = policy.minDwellTicks();
            if (dwell < required) {
                long remaining = required - dwell;
                log.log(Level.WARNING, "会话 {0} 最短驻留不足,切换被拒: 剩 {1} tick(要求 {2})",
                        new Object[] { session.id().value(), remaining, required });
                future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                        "驻留不足,剩 " + remaining + " tick(要求 " + required + ")"));
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

    /** A3 地图解析:指定地图用之;否则按模式标签 × 会话地图池过滤,空集拒绝;非空取装载序第一张。 */
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
            if (Collections.disjoint(mode.mapTags(), candidate.tags())) {
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
                if (previousWorld != null) {
                    worlds.release(previousWorld);
                }
            }
        } catch (Throwable t) {
            rollback(session, plan, t);
        }
    }

    /** B 段失败回退:原请求以拒绝完结 → 会话回常态 → 递归切换回退模式(一级)。 */
    private void rollback(Session session, PendingSwitch plan, Throwable cause) {
        log.log(Level.SEVERE, "切换执行失败,进入回退: 会话 " + session.id().value()
                + " 目标 " + plan.mode.id(), cause);
        plan.future.complete(rejected(SwitchResult.Reason.REJECTED_BY_TARGET_MODE,
                "目标模式装配失败: " + cause.getMessage()));
        if (session.state() == SessionState.SWITCHING) {
            session.transitionTo(SessionState.RUNNING);    // 旧实例已清、当前无实例,回常态再递归
        }
        session.setCurrentInstance(null);
        fallbackSwitch(session);
    }

    /**
     * 一级回退:递归切换到配置的回退模式(id 来自构造注入的配置字符串,源码零字面量;
     * 回退是框架内部自愈,带豁免标记绕过人数下限与最短驻留)。回退亦失败 → 二级解散。
     *
     * <p><b>空值语义</b>:回退模式<b>未配置(空串)</b> → 跳过一级(fallback 切换)、
     * 记 WARN 后直接二级解散兜底;回退模式<b>未注册</b> → 走既有路径
     * (A1 拒绝 → whenComplete → 二级解散),机制零改动。
     */
    private void fallbackSwitch(Session session) {
        if (fallbackModeId.isBlank()) {
            log.log(Level.WARNING, "会话 {0} 回退模式未配置: 跳过一级回退,直接二级解散兜底",
                    session.id().value());
            dissolveNow(session, DissolveReason.FALLBACK_DISSOLVE);
            return;
        }
        CompletableFuture<SwitchResult> fallback = beginSwitch(session,
                new MatchModeSelection(fallbackModeId, Optional.empty(), true),
                Handoff.empty(), EndResult.nowhere());
        fallback.whenComplete((result, err) -> {
            if (err != null || result instanceof SwitchResult.Rejected) {
                dissolveNow(session, DissolveReason.FALLBACK_DISSOLVE);
            }
        });
    }

    // ── 自动切换(触发来源②③:节拍器在 ENDING 后的下一 tick 驱动) ─────────

    /**
     * ENDING 处置:读结束请求的显式意图;无意图时读模式缺省去向
     * (回退/原地/轮换——轮换暂无轮换表配置,按回退降级处理)。
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
        MatchModeSelection selection;
        Handoff payload;
        if (result.transition().isPresent()) {
            TransitionIntent intent = result.transition().get();
            selection = intent.target();
            payload = intent.payload();
        } else {
            String currentModeId = session.currentInstance().map(MatchModeInstance::modeId)
                    .orElseGet(() -> session.spec().initialModeId());
            selection = switch (defaultTransitionOf(currentModeId)) {
                case STAY -> MatchModeSelection.of(currentModeId);      // 原地重开(同模式新实例)
                case FALLBACK, ROTATION -> MatchModeSelection.of(fallbackModeId);  // 轮换暂按回退降级
            };
            payload = Handoff.empty();
        }
        beginSwitch(session, selection, payload, result).whenComplete((outcome, err) -> {
            // 同步段被拒(如最短驻留):回填结束请求,下一拍重试直至防护放行
            if (err == null && outcome instanceof SwitchResult.Rejected
                    && session.state() == SessionState.ENDING) {
                session.restoreEndNotices(notices);
            }
        });
    }

    /** 模式缺省去向(注册表查不到时按回退处理——模式被摘除的边界)。 */
    private MatchModeMeta.DefaultTransition defaultTransitionOf(String modeId) {
        try {
            return modes.require(modeId).meta().defaultTransition();
        } catch (RuntimeException e) {
            return MatchModeMeta.DefaultTransition.FALLBACK;
        }
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
        current.admitParticipant(player);      // 参赛/观战位的安置差别归组件层
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
     * 成员传送主城出生点(三级:失败保持原地)→ 释放世界句柄接缝 → 兜底清态 → 摘除 →
     * 发解散事件。会话组件标签集随会话终结一并消灭(A2 并集不再含它,D6)。
     */
    private void dissolveNow(Session session, DissolveReason reason) {
        if (session.state() == SessionState.DISSOLVING) {
            return;                     // 幂等:解散中重复调用直接返回
        }
        abortPending(session, "会话解散,挂起切换作废");
        session.transitionTo(SessionState.DISSOLVING);
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
            // 释放世界句柄兜底(§3.7 释放时机行:dissolve 的 finally;§14.19)。
            // 先摘再释放:dissolveNow 幂等守卫之外的双保险,release 自身亦幂等。
            WorldHandle heldWorld = session.worldHandle();
            if (heldWorld != null) {
                session.replaceWorldHandle(null);
                worlds.release(heldWorld);
            }
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
     * 读口现取(名册/tick/attrs/当前实例);两个行动口(D8)镜像实例级:
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
        public AttributeBag attrs() {
            return session.attrs();
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
            try {
                session.currentInstance().ifPresent(MatchModeInstance::close);
            } catch (Throwable t) {
                log.log(Level.WARNING, "隔离时关闭实例异常(继续): " + session.id().value(), t);
            }
            session.setCurrentInstance(null);
            if (session.state() == SessionState.SWITCHING) {
                session.transitionTo(SessionState.RUNNING);    // 回常态再递归(ENDING 可直达切换)
            }
            fallbackSwitch(session);
        }
    }

    // ── 构造器(依赖全部注入;离线测试可替换每一跳) ─────────────────────

    /** 装配构造器:必填项缺失在 {@link #build()} 响亮抛。 */
    public static final class Builder {

        private ModeLookup modes;
        private CapabilityRegistry capabilities;
        private WorldPort worlds;
        private MapRegistry maps;
        private Function<String, MatchModeConfig> configSource;
        private SessionPolicy policy;
        private String fallbackModeId;
        private EngineServices engineServices;
        private Executor mainExecutor = Runnable::run;
        private Consumer<Collection<UUID>> hubRelocator = members -> { };
        private Consumer<Collection<UUID>> safeStateCleaner = members -> { };
        private Consumer<SessionEvent> sessionEventMirror;
        private Logger logger = Logger.getLogger(SessionServiceImpl.class.getName());

        /** 模式查询口(生产 = 模式注册表;测试 = 可控假实现)。 */
        public Builder modes(ModeLookup modes) {
            this.modes = Objects.requireNonNull(modes, "modes");
            return this;
        }

        public Builder capabilities(CapabilityRegistry capabilities) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
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
         * 回退模式 id(配置注入,引擎零字面量;§14.21)。允许空串——
         * 空 = 回退链跳过一级、直接二级解散兜底;仅 {@code null} 视为装配缺失。
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
            if (capabilities == null) missing.append("capabilities ");
            if (worlds == null) missing.append("worlds ");
            if (maps == null) missing.append("maps ");
            if (configSource == null) missing.append("configSource ");
            if (policy == null) missing.append("policy ");
            if (fallbackModeId == null) missing.append("fallbackModeId ");   // 空串合法(跳级语义),仅 null 缺失
            if (engineServices == null) missing.append("engineServices ");
            if (!missing.isEmpty()) {
                throw new IllegalStateException("SessionServiceImpl 依赖缺失: " + missing);
            }
            return new SessionServiceImpl(this);
        }
    }
}
