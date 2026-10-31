package com.hydrokarbs.component.phase;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.instance.MatchModeInstanceView;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.port.SessionView;
import com.hydrokarbs.map.MapDefinition;

/**
 * 阶段状态机组件:持有阶段表数据,推进/转场/计时(施工计划 1.5;06-共享组件 §6.1-#4)。
 *
 * <p>★ 阶段状态唯一写者 = 本组件:当前索引与进入时刻全在组件内,
 * 其他组件经读口({@link #currentId}/{@link #currentExpired}/{@link #countdown})
 * 或订阅 {@link PhaseChanged} 消费——这是仅有的两条合法路径(14-陷阱清单 §14.24)。
 * 引擎对「阶段」零认知;不要阶段推进的模式不挂本组件(要就挂、不要不挂)。
 *
 * <p>推进算法(数据驱动,组件内不按具体阶段 id 分支——阶段差异只在表里):
 * <ul>
 *   <li>{@code start()}:进入首阶段并发布 {@link PhaseChanged}(fromPhaseId = null);</li>
 *   <li>{@code update()}:当前条目等待型则求值谓词、计时型则看停留时长,走完即转下一阶段,
 *       每次转场发布 {@link PhaseChanged};末阶段走完后按 {@code loopOnFinish}
 *       循环回首阶段或原地停留(停留语义供常驻型模式使用);</li>
 *   <li>{@link #jump}:显式跳转(声明式规则的 then 跨组件驱动阶段推进的口),
 *       未知 id 响亮抛 {@link IllegalArgumentException}。</li>
 * </ul>
 *
 * <p>类注:实例只读视图的来源——{@link MatchModeInstanceView} 无服务集直通口
 * (「组件拿 view」的通道在前置子阶段未落接口),本组件以嵌套适配器把视图面
 * 委托到 {@code svc()} 能取到的读口;{@code session()} 已随 2.6 接通真只读视图
 * (经 {@code svc().self().session()} 委托;阶段1 已知问题 4 消除,记 deviations)。
 */
public final class PhaseComponent extends MatchModeComponent {

    private final List<PhaseSpec> phases;
    private final boolean loopOnFinish;
    private final InstanceView view = new InstanceView();   // 无状态适配器,构造期建一次

    private int currentIndex = -1;      // -1 = 未进入任何阶段(start 之前)
    private long enteredAtTick;
    private boolean started;

    /** 装配描述符:声明序 = 推进序;loopOnFinish 缺省 false = 停在末阶段。 */
    public static final class Spec extends MatchModeComponent.Specification<PhaseComponent> {

        private final List<PhaseSpec> phases;
        private final boolean loopOnFinish;

        public Spec(List<PhaseSpec> phases) {
            this(phases, false);
        }

        public Spec(List<PhaseSpec> phases, boolean loopOnFinish) {
            super("phase");
            if (phases == null || phases.isEmpty()) {
                throw new IllegalArgumentException("阶段表为空:不要阶段推进的模式不应挂本组件(要就挂、不要不挂)");
            }
            this.phases = List.copyOf(phases);
            this.loopOnFinish = loopOnFinish;
        }

        @Override
        public PhaseComponent create(String id, MatchModeInstanceServices svc) {
            return new PhaseComponent(id, svc, phases, loopOnFinish);
        }
    }

    PhaseComponent(String id, MatchModeInstanceServices svc, List<PhaseSpec> phases, boolean loopOnFinish) {
        super(id, svc);
        this.phases = phases;
        this.loopOnFinish = loopOnFinish;
    }

    // ── 生命周期 ────────────────────────────────────────────────

    /** 进入首阶段并发布转场事件(首阶段为等待型且未满足时,停在首阶段等待)。 */
    @Override
    public void start() {
        enter(0, null, 0L);
    }

    /** 每 tick:当前条目走完即转下一阶段;末阶段走完按 loopOnFinish 循环或停留。 */
    @Override
    public void update() {
        if (!started || !currentExpired()) {
            return;
        }
        boolean last = currentIndex == phases.size() - 1;
        if (last && !loopOnFinish) {
            return;     // 末阶段停留
        }
        int next = last ? 0 : currentIndex + 1;
        transitionTo(next);
    }

    // ── 读口(状态唯一主人的强类型读口) ────────────────────────────

    /** 当前阶段 id;start 之前返回 null。 */
    public String currentId() {
        return started ? phases.get(currentIndex).id() : null;
    }

    /** 当前阶段是否已走完(等待型 = 谓词为真;计时型 = 停留时长到点)。 */
    public boolean currentExpired() {
        if (!started) {
            return false;
        }
        PhaseSpec current = phases.get(currentIndex);
        return switch (current.kind()) {
            case AWAIT -> current.entryWhen().test(view());
            case TIMED -> elapsedInCurrent() >= current.durationTicks();
        };
    }

    /** 当前阶段的倒计时(仅计时型有;等待型或未 start 返回 empty)。
     *  表现层的中立倒计时模型由显示组件翻译,本组件不反向依赖表现层。 */
    public Optional<PhaseCountdown> countdown() {
        if (!started) {
            return Optional.empty();
        }
        PhaseSpec current = phases.get(currentIndex);
        if (current.kind() != PhaseSpec.Kind.TIMED) {
            return Optional.empty();
        }
        int total = current.durationTicks();
        long elapsed = Math.min(elapsedInCurrent(), total);
        return Optional.of(new PhaseCountdown(total - (int) elapsed, total));
    }

    // ── 显式跳转 ────────────────────────────────────────────────

    /** 跳到指定阶段(声明式规则的 then 跨组件驱动阶段推进用);未知 id 响亮失败。 */
    public void jump(String phaseId) {
        Objects.requireNonNull(phaseId, "phaseId");
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i).id().equals(phaseId)) {
                transitionTo(i);
                return;
            }
        }
        throw new IllegalArgumentException("未知阶段 id: " + phaseId + "(组件 " + id() + ")");
    }

    // ── 内部:转场与计时(状态的唯一写点) ─────────────────────────

    /** 从当前位置转到目标索引:记录前驱停留时长并发布转场事件。 */
    private void transitionTo(int targetIndex) {
        String from = currentId();                       // 未 start 时为 null
        long elapsed = started ? elapsedInCurrent() : 0L;
        enter(targetIndex, from, elapsed);
    }

    /** 进入指定索引的阶段并发布事件(本组件唯一的写点)。 */
    private void enter(int index, String fromPhaseId, long elapsedTicks) {
        currentIndex = index;
        started = true;
        long now = svc().self().tick();
        enteredAtTick = now;
        svc().self().events().publish(new PhaseChanged(
                fromPhaseId, phases.get(index).id(), elapsedTicks, (int) now, System.currentTimeMillis()));
    }

    /** 当前阶段已停留 tick 数(进入当拍为 0)。 */
    private long elapsedInCurrent() {
        return Math.max(0, svc().self().tick() - enteredAtTick);
    }

    /** 实例只读视图(嵌套适配器:委托服务集读口;session() 见类注)。 */
    private MatchModeInstanceView view() {
        return view;
    }

    /** 服务集背后的只读视图(等待型谓词求值的实参来源)。 */
    private final class InstanceView implements MatchModeInstanceView {

        @Override
        public <T extends MatchModeComponent> T component(Class<T> type) {
            return svc().components().get(type);
        }

        @Override
        public MatchModeConfig cfg() {
            return svc().self().cfg();
        }

        @Override
        public MapDefinition map() {
            return svc().self().map();
        }

        @Override
        public long tick() {
            return svc().self().tick();
        }

        @Override
        public SessionView session() {
            return svc().self().session();   // 2.6 接通:经服务集自面取所属会话的只读视图
        }
    }

    /** 计时型阶段的倒计时读数(组件自有模型,不反向依赖表现层)。 */
    public record PhaseCountdown(int remainingTicks, int totalTicks) {
    }
}
