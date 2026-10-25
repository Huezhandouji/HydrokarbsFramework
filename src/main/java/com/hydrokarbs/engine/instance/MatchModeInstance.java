package com.hydrokarbs.engine.instance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.component.ComponentRegistry;
import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.event.MatchModeInstanceEvent;
import com.hydrokarbs.engine.event.MatchModeInstanceEventBus;
import com.hydrokarbs.engine.event.MatchModeInstanceEventPort;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.JoinAdmission;
import com.hydrokarbs.engine.port.JoinContext;
import com.hydrokarbs.engine.port.JoinDecision;
import com.hydrokarbs.engine.port.MatchModeInstancePort;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.port.MatchModeInfoPort;
import com.hydrokarbs.engine.port.MatchModeInstanceAccess;
import com.hydrokarbs.engine.port.SessionView;
import com.hydrokarbs.engine.port.WorldHandle;
import com.hydrokarbs.engine.session.EndResult;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.HandoffView;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionMember;
import com.hydrokarbs.engine.session.TransitionIntent;
import com.hydrokarbs.map.MapDefinition;

/**
 * 一次模式运行的容器(12-代码骨架 §12.2)。
 *
 * <p>两相纪律(「构造器只做不可见的事」):
 * <ul>
 *   <li>构造器:先建 svc 服务集(self/components/modeInfo 三个实现体为本类内部类,
 *       捕获容器自身——解决「svc 先于组件存在」的次序问题),再按模式声明序
 *       逐条造组件并登记(构造点唯一),组件的事件钩子装配期挂总线——
 *       构造失败零玩家痕迹(这正是阶段 2 切换回退判据的结构前提,§3.2-B 注);</li>
 *   <li>{@link #awakeAll()} / {@link #startAll()}:显式二段,玩家可见副作用
 *       最早发生在 startAll 且至多一次。</li>
 * </ul>
 *
 * <p>★ 不持有玩法状态,也不持有组件长期引用——装配在构造器用局部变量完成后,
 * 一切按 id/类型现取(02-核心概念 §2.8-决断三)。
 * 唯一对外职责:装配 / 派发(声明序)/ 供端口 / 回收登记资源;
 * {@link #tick()} 只派发、不解释——方法体内不允许出现第二类逻辑(14-陷阱清单 §14.24)。
 */
public final class MatchModeInstance implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MatchModeInstance.class.getName());

    /** 进程内实例序号(id 生成的尾段)。 */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final MatchModeInstanceId id;
    private final Session session;
    private final MatchMode mode;                   // 只读:模板信息
    private final MapDefinition map;
    private final MatchModeConfig cfg;
    private final WorldHandle world;                // 世界句柄权威在会话;容器保存仅供装配期
    private final Handoff handoff;
    private final MatchModeInstanceClock clock;
    private final ComponentRegistry components;     // 声明序:id → 组件实例
    private final MatchModeInstanceEventBus bus;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final MatchModeInstanceServices svc;    // 组件共享的服务集(self/components/modeInfo)
    private final AttributeBag attrs = new AttributeBag();
    private final EngineServices engine;
    private final SelfPort self;

    private volatile boolean closed;
    private volatile boolean ending;                // 结束请求置位(self().end() 的落点)
    private volatile boolean quarantined;           // 同实例只隔离一次的护栏

    /** 由 {@link MatchMode#createInstance} 调用(依赖已校验)。 */
    public MatchModeInstance(MatchMode mode, Session session, MapDefinition map, MatchModeConfig cfg,
                             WorldHandle world, Handoff handoff, EngineServices engine) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.session = Objects.requireNonNull(session, "session");
        this.map = Objects.requireNonNull(map, "map");
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.world = world;
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.id = new MatchModeInstanceId(mode.id() + "-" + SEQUENCE.incrementAndGet());
        this.clock = new MatchModeInstanceClock();
        this.bus = new MatchModeInstanceEventBus();
        this.components = new ComponentRegistry();
        this.self = new SelfPort();
        this.svc = new MatchModeInstanceServices(self, components, new ModeInfoView());
        // 两相纪律:构造器只做不可见的事——按声明序逐条造组件并登记(构造点唯一,§2.4-④)。
        // 构造中途失败:已造组件未 start(无玩家痕迹),总线与注册表随本容器一并丢弃,无泄漏。
        for (Map.Entry<String, MatchMode.ComponentEntry> entry : mode.components().entrySet()) {
            MatchModeComponent created = entry.getValue().factory().create(entry.getKey(), svc);
            components.register(created, entry.getValue());
        }
        // 组件的事件钩子装配期挂总线(§2.4 onEvent javadoc:订阅发生在构造/awake 期,
        // 随 close 的 bus.clear() 自动回收)。
        for (MatchModeComponent component : components.inDeclarationOrder()) {
            bus.subscribe(MatchModeInstanceEvent.class, component::onEvent);
        }
        // 授权表灌入:组件全部装配完成后,把 (id → 类) 对灌进实例袋——
        // 命名空间断言的授权表由此而来,容器是唯一合法灌表方。
        bindAuthorized(attrs);
    }

    // ── 二段生效(调用方:会话服务的切换执行段 B4;2.3 起跨包调用,提 public) ────

    /** 装配段:声明序逐组件 awake——只解析依赖,玩家不可见。 */
    public void awakeAll() {
        components.inDeclarationOrder().forEach(MatchModeComponent::awake);
    }

    /**
     * 生效段:名册原地位移(经入场裁决触发点①)→ 声明序逐组件 start → 发布实例启动事件。
     *
     * <p>★v3.2 触发点①(03-会话与模式切换 §3.2.1):成员成为参赛者之前逐人经
     * {@link JoinAdmission} 裁决——按组件声明序取第一个实现者,无实现者默认 ADMIT;
     * REJECT 的成员留在会话名册、不进本实例(成员仍有所属会话,只是本局不上场);
     * ADMIT/SPECTATE 都成为参赛者,观战位的安置体验归组件层。
     */
    public void startAll() {
        JoinAdmission gate = joinGate();
        JoinContext ctx = new JoinContext(JoinContext.Trigger.INITIAL_PLACEMENT, session.id());
        for (SessionMember member : session.roster().all()) {
            JoinDecision decision = gate != null
                    ? gate.admit(member.uuid(), ctx)
                    : new JoinDecision(JoinDecision.Kind.ADMIT, null);   // 无实现者默认 ADMIT
            if (decision.kind() == JoinDecision.Kind.REJECT) {
                continue;                       // 本局不上场:名册保留,不进参赛者表
            }
            participants.put(member.uuid(), new Participant(member.uuid()));   // 名册原地位移
        }
        // 参赛者袋由名册在容器外创建,容器放置参赛者时统一补灌同一份授权表
        // (组件最早的触达点是随后的 start,灌表先于一切组件可写路径)。
        participants.values().forEach(participant -> bindAuthorized(participant.attrs()));
        components.inDeclarationOrder().forEach(MatchModeComponent::start);
        bus.publish(new MatchModeInstanceEvent.MatchModeInstanceStarted(clock.tick(), clock.occurredAtMillis()));
    }

    // ── 装配期灌表(命名空间授权表的唯一合法来源) ───────────────────

    /**
     * 遍历组件注册表既有读口(声明序快照),把 (组件 id → 组件类) 灌入指定袋的
     * 授权表——实例袋与各参赛者袋共用本口,灌表逻辑不散落多处。
     */
    private void bindAuthorized(AttributeBag bag) {
        for (MatchModeComponent component : components.inDeclarationOrder()) {
            bag.bindComponent(component.id(), component.getClass());
        }
    }

    // ── 切换配合口(2.3:会话服务切换阶段 B1/B4 与 join 触发点②的落点) ────────

    /**
     * 交接出局(切换阶段 B1):按声明序逐组件 {@code onHandoff(Builder)}——
     * 组件状态尚完整,但只准写交接载荷、不准碰世界/玩家。收拢各组件写入后返回载荷。
     */
    public Handoff dispatchHandoff() {
        Handoff.Builder out = new Handoff.Builder();
        components.inDeclarationOrder().forEach(component -> component.onHandoff(out));
        return out.build();
    }

    /**
     * 入场裁决口:按组件声明序取第一个 {@link JoinAdmission} 实现者;
     * 无实现者返回 null(调用方按默认 ADMIT 处置,§3.2.1 触发点表)。
     */
    public JoinAdmission joinGate() {
        for (MatchModeComponent component : components.inDeclarationOrder()) {
            if (component instanceof JoinAdmission gate) {
                return gate;
            }
        }
        return null;
    }

    /**
     * 运行中加入的入册口(join 触发点②的实例侧落点,§3.2.1):
     * 名册登记后由会话服务调用——参赛者入册并发结构事件。
     * 已在册为幂等 no-op(返回既有参赛者)。
     */
    public Participant admitParticipant(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        Participant existing = participants.get(uuid);
        if (existing != null) {
            return existing;
        }
        Participant fresh = new Participant(uuid);
        bindAuthorized(fresh.attrs());            // 与初始摆放同一授权表口径
        participants.put(uuid, fresh);
        bus.publish(new MatchModeInstanceEvent.ParticipantJoined(clock.tick(), clock.occurredAtMillis(), uuid));
        return fresh;
    }

    /** 本实例运行的模式 id(会话侧事件载荷与回退诊断用;2.3 增)。 */
    public String modeId() {
        return mode.id();
    }

    // ── 驱动与收口 ────────────────────────────────────────────────

    /** 由全局节拍器每 tick 调用(主线程)。★ 只派发,不解释。 */
    public void tick() {
        if (closed) {
            return;
        }
        clock.tick();
        try {
            components.forEachTicking(MatchModeComponent::update);   // 声明序;组件自驱
        } catch (Throwable t) {
            quarantine(t);
        }
    }

    /** 实例自面端口的 end 落点:只置位与通知会话,不清场(节拍器下一 tick 处置,§3.1)。 */
    void requestEnd(EndResult result) {
        Objects.requireNonNull(result, "result");
        if (ending || closed) {
            return;
        }
        ending = true;
        session.instanceEnded(this, result);
    }

    /** ★ 幂等(closed 护栏,连调多次只清一次)。逆序 stop(逐个隔离)→ 统一回收登记资源 → 清总线。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        components.inReverseDeclarationOrder().forEach(this::safeStop);   // 逆序;异常不中断
        components.reclaimRegisteredResources();                          // 订阅/延时任务/显示统一回收
        bus.clear();
    }

    /** 同实例只隔离一次(volatile 护栏);记 SEVERE 并请求结束(无显式去向)。 */
    private void quarantine(Throwable t) {
        if (quarantined) {
            return;
        }
        quarantined = true;
        LOG.log(Level.SEVERE, "实例故障隔离: " + id.value(), t);
        requestEnd(EndResult.nowhere());
    }

    private void safeStop(MatchModeComponent component) {
        try {
            component.stop();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "组件 stop 异常(已隔离,继续逆序): " + component.id(), t);
        }
    }

    // ── later 的实现:句柄登记到发起组件名下(close 统一取消) ──────────

    private void schedule(int delayTicks, Runnable task) {
        Objects.requireNonNull(task, "task");
        AutoCloseable handle = engine.platform().scheduleOnce(delayTicks, task);
        components.registerResource(callerComponent(), handle);
    }

    /**
     * 反查调用栈上最近的组件声明类,再经注册表取其实例(资源归属;
     * 同类多实例的罕见边界下取声明序第一个,归属精度仅供诊断)。
     */
    private MatchModeComponent callerComponent() {
        Class<?> frame = StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE))
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(owner -> owner != MatchModeInstance.class
                                && MatchModeComponent.class.isAssignableFrom(owner))
                        .findFirst()
                        .orElse(null));
        return frame != null ? components.get(frame.asSubclass(MatchModeComponent.class)) : null;
    }

    // ── 读口 ──────────────────────────────────────────────────────

    public MatchModeInstanceId id() {
        return id;
    }

    /** 实例只读读口(内容侧消费;实现体为内部类,捕获容器自身)。 */
    public MatchModeInstanceView view() {
        return self;
    }

    /**
     * 会话侧只读实例面(★v4-D5):{@code SessionPort#currentInstance()} 的元素——
     * 会话级组件经它读当前实例运行数据。每次调用<b>现取</b>(调用方不得缓存引用
     * 越过切换,14.27);无写口(只读是架构纪律 + review 条款,14.29)。
     */
    public MatchModeInstanceAccess access() {
        return new AccessView();
    }

    /** 按类型取第一个可赋值组件(容器直读口,§12.2 监听器的 {@code i.component(X.class)} 用法)。 */
    public <T extends MatchModeComponent> T component(Class<T> type) {
        return components.get(type);
    }

    /** 入口交接载荷(只读视图)。 */
    public HandoffView handoff() {
        return handoff;
    }

    /** 本实例的参赛者快照(2.3 增:名册位移链路断言与会话侧只读消费;不可变列表)。 */
    public List<Participant> participants() {
        return List.copyOf(participants.values());
    }

    /** 按 uuid 取参赛者;不在本实例返回 empty。 */
    public Optional<Participant> participant(UUID uuid) {
        return Optional.ofNullable(participants.get(uuid));
    }

    // ── 内部实现体:self 端口组(含只读读口)与模式信息面 ───────────────

    /**
     * 实例自面({@link MatchModeInstancePort})与只读读口({@link MatchModeInstanceView})
     * 的实现体——同一对象、两个视图;捕获容器自身。
     */
    private final class SelfPort implements MatchModeInstancePort, MatchModeInstanceView {

        @Override
        public List<Participant> participants() {
            return List.copyOf(participants.values());
        }

        @Override
        public Optional<Participant> participant(UUID uuid) {
            return Optional.ofNullable(participants.get(uuid));
        }

        @Override
        public long tick() {
            return clock.currentTick();
        }

        @Override
        public AttributeBag attrs() {
            return attrs;
        }

        @Override
        public HandoffView handoff() {
            return handoff;
        }

        @Override
        public MapDefinition map() {
            return map;
        }

        @Override
        public MatchModeConfig cfg() {
            return cfg;
        }

        @Override
        public MatchModeInstanceEventPort events() {
            return bus;
        }

        @Override
        public void later(int delayTicks, Runnable task) {
            MatchModeInstance.this.schedule(delayTicks, task);
        }

        @Override
        public void end(EndResult result) {
            requestEnd(result);
        }

        @Override
        public void switchTo(MatchModeSelection target, Handoff payload) {
            // 2.3 接线:切换请求走「结束 + 显式去向」——与结束判定组件的主动声明同一通道
            // (§3.2 三个触发入口殊途同归到会话切换一个方法;请求只置位,处置由节拍器
            // 下一 tick 驱动,切换永远不发生在事件派发栈内,§3.1/§16.4-三律①)。
            requestEnd(new EndResult(Optional.of(new TransitionIntent(target, payload))));
        }

        // ── MatchModeInstanceView ──

        @Override
        public <T extends MatchModeComponent> T component(Class<T> type) {
            return components.get(type);
        }

        @Override
        public <T> Optional<T> attr(AttributeBag.Key<T> key) {
            return attrs.get(key);
        }

        @Override
        public SessionView session() {
            // ★v4-D5:实例侧只读会话视图(同一方法双满足 MatchModeInstancePort#session()
            // 与本只读读口;原裸 Session 返回在 2.6 收窄,阶段1 已知问题 4 消除)
            return session.view();
        }
    }

    /**
     * 会话侧只读实例面(★v4-D5)的实现体:委托容器自身字段与组件注册表;
     * 只读约定(仅 get/无写口)是架构纪律 + review 条款(14.29)。
     */
    private final class AccessView implements MatchModeInstanceAccess {

        @Override
        public MatchModeInstanceId id() {
            return id;
        }

        @Override
        public String modeId() {
            return mode.id();
        }

        @Override
        public long tick() {
            return clock.currentTick();
        }

        @Override
        public List<Participant> participants() {
            return List.copyOf(participants.values());
        }

        @Override
        public MatchModeInfoPort modeInfo() {
            return svc.modeInfo();
        }

        @Override
        public AttributeBag attrs() {
            return attrs;
        }

        @Override
        public <T extends MatchModeComponent> T component(Class<T> type) {
            return components.get(type);
        }

        @Override
        public <T extends MatchModeComponent> List<T> components(Class<T> type) {
            return components.getAll(type);
        }
    }

    /** 模式模板只读信息面:委托聚合根的只读访问器。 */
    private final class ModeInfoView implements MatchModeInfoPort {

        @Override
        public String id() {
            return mode.id();
        }

        @Override
        public Component displayName() {
            return mode.displayName();
        }

        @Override
        public List<Component> description() {
            return mode.description();
        }

        @Override
        public Material icon() {
            return mode.icon();
        }

        @Override
        public Set<String> mapTags() {
            return mode.mapTags();
        }

        @Override
        public MatchModeMeta meta() {
            return mode.meta();
        }
    }
}
