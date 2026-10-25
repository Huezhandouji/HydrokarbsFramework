package com.hydrokarbs.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.port.SessionPort;
import com.hydrokarbs.engine.port.SessionServices;

/**
 * 会话级探针组件(2.6 测试基座):五钩子按序记录 + 可注入失败点(awake/start/update/
 * onEvent 抛)+ 能力标签 + start 期订阅开关 + later 登记——「注册表按声明序、无条件
 * 广播」与 D9 隔离的度量衡。断言的是派发序与隔离语义,不是策略内容。
 *
 * <p>★ 零 Bukkit import;观察包装({@link #sibling}/{@link #self}/{@link #recordLater})
 * 把受保护的服务集口开放给测试。与实例侧 {@link ProbeComponent} 分工对称。
 */
public final class SessionProbeComponent extends SessionComponent {

    private final Spec spec;
    private final List<String> log;
    private final List<SessionEvent> received = new ArrayList<>();
    private int updates;

    private SessionProbeComponent(String id, SessionServices svc, Spec spec) {
        super(id, svc);
        this.spec = spec;
        this.log = spec.log;
    }

    // ── Spec 工厂(descriptorLabel = "session-probe") ────────────────

    /** 最小件:只记录钩子。 */
    public static Spec spec(List<String> log) {
        return new Spec(log);
    }

    /** 带每拍回调(跨级只读等观察断言用)。 */
    public static Spec spec(List<String> log, Consumer<SessionProbeComponent> onUpdate) {
        return new Spec(log).onUpdate(onUpdate);
    }

    /** 带能力标签(★32:A2 可用集并集断言)。 */
    public static Spec tagged(List<String> log, String... tags) {
        Spec spec = new Spec(log);
        spec.capabilities(tags);
        return spec;
    }

    /** 会话级探针描述符(失败点与行为全可配;默认只记录钩子)。 */
    public static final class Spec extends SessionComponent.Specification<SessionProbeComponent> {

        private final List<String> log;
        boolean awakeThrows;
        String awakeMessage = "session-probe-awake-boom";
        boolean startThrows;
        String startMessage = "session-probe-start-boom";
        boolean updateThrows;
        String updateMessage = "session-probe-update-boom";
        boolean onEventThrows;
        String onEventMessage = "session-probe-onEvent-boom";
        boolean subscribeOnStart;
        boolean laterOnStart;
        int laterDelayTicks = 1;
        Consumer<SessionProbeComponent> onStart;
        Consumer<SessionProbeComponent> onUpdate;

        public Spec(List<String> log) {
            super("session-probe");
            this.log = Objects.requireNonNull(log, "log");
        }

        /** 装配期唤醒抛(会话创建失败语义注入,★30 装配期分支)。 */
        public Spec failOnAwake(String message) {
            this.awakeThrows = true;
            this.awakeMessage = message;
            return this;
        }

        /** 装配期生效抛(会话创建失败语义注入,★30 装配期分支)。 */
        public Spec failOnStart(String message) {
            this.startThrows = true;
            this.startMessage = message;
            return this;
        }

        /** 运行期 update 抛(D9 隔离注入)。 */
        public Spec failOnUpdate(String message) {
            this.updateThrows = true;
            this.updateMessage = message;
            return this;
        }

        /** 运行期 onEvent 抛(D9 隔离注入)。 */
        public Spec failOnEvent(String message) {
            this.onEventThrows = true;
            this.onEventMessage = message;
            return this;
        }

        /** start 期订阅会话事件(句柄登记名下,随 stop/解散回收,★28)。 */
        public Spec subscribeOnStart() {
            this.subscribeOnStart = true;
            return this;
        }

        /** start 期登记延迟任务(句柄登记名下,随 stop/解散回收,★28)。 */
        public Spec laterOnStart(int delayTicks) {
            this.laterOnStart = true;
            this.laterDelayTicks = delayTicks;
            return this;
        }

        /** 生效回调(观察/断言辅助)。 */
        public Spec onStart(Consumer<SessionProbeComponent> onStart) {
            this.onStart = onStart;
            return this;
        }

        /** 每拍回调(观察/断言辅助)。 */
        public Spec onUpdate(Consumer<SessionProbeComponent> onUpdate) {
            this.onUpdate = onUpdate;
            return this;
        }

        @Override
        public SessionProbeComponent create(String id, SessionServices svc) {
            return new SessionProbeComponent(id, svc, this);
        }
    }

    // ── 钩子:全部按序记录 ───────────────────────────────────────

    @Override
    public void awake() {
        log.add(id() + ":awake");
        if (spec.awakeThrows) {
            throw new IllegalStateException(spec.awakeMessage);
        }
    }

    @Override
    public void start() {
        log.add(id() + ":start");
        if (spec.startThrows) {
            throw new IllegalStateException(spec.startMessage);
        }
        if (spec.subscribeOnStart) {
            svc().self().events().subscribe(SessionEvent.class, received::add);
        }
        if (spec.laterOnStart) {
            svc().self().later(spec.laterDelayTicks, () -> log.add(id() + ":later-fired"));
        }
        if (spec.onStart != null) {
            spec.onStart.accept(this);
        }
    }

    @Override
    public void update() {
        log.add(id() + ":update");
        updates++;
        if (spec.onUpdate != null) {
            spec.onUpdate.accept(this);
        }
        if (spec.updateThrows) {
            throw new IllegalStateException(spec.updateMessage);
        }
    }

    @Override
    public void onEvent(SessionEvent event) {
        log.add(id() + ":onEvent:" + event.getClass().getSimpleName());
        received.add(event);
        if (spec.onEventThrows) {
            throw new IllegalStateException(spec.onEventMessage);
        }
    }

    @Override
    public void stop() {
        log.add(id() + ":stop");
    }

    // ── 观察包装(把受保护的服务集口开放给测试) ───────────────────

    /** 按类型取同会话内的另一组件(svc().components().get 的公开包装)。 */
    public <T extends SessionComponent> T sibling(Class<T> type) {
        return svc().components().get(type);
    }

    /** 会话自面(svc().self() 的公开包装——跨级只读/行动口断言用)。 */
    public SessionPort self() {
        return svc().self();
    }

    /** 已经历的 update 拍数(★33 计数连续断言口)。 */
    public int updates() {
        return updates;
    }

    /** 经订阅面收到的会话事件(★28 订阅/回收断言口)。 */
    public List<SessionEvent> received() {
        return received;
    }
}
