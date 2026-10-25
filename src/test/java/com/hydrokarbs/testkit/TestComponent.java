package com.hydrokarbs.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.event.MatchModeInstanceEvent;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.EndResult;
import com.hydrokarbs.engine.session.Handoff;

/**
 * 测试假组件(施工计划 1.5;test 源集,全阶段共用):按序记录钩子调用,
 * 是「容器按声明序、无条件广播」的度量衡——断言的是派发序,不是玩法语义。
 *
 * <p>★ 零 Bukkit import;{@code onHandoff} 写入自己命名空间(组件实例 id)的
 * 交接键,供交接链路断言。观察包装({@link #sibling}/{@link #publish}/
 * {@link #recordLater})把受保护的服务集口开放给测试 lambda。
 */
public final class TestComponent extends MatchModeComponent {

    /** onHandoff 写入的键名(namespace = 组件实例 id,键随主人)。 */
    public static final String TOKEN_NAME = "token";

    private final List<String> log;
    private final Consumer<TestComponent> onStart;
    private final Consumer<TestComponent> onUpdate;

    /** 收到的全部实例事件(装配期钩子挂总线,随 close 清空)。 */
    public final List<MatchModeInstanceEvent> received = new ArrayList<>();

    private TestComponent(String id, MatchModeInstanceServices svc, List<String> log,
                          Consumer<TestComponent> onStart, Consumer<TestComponent> onUpdate) {
        super(id, svc);
        this.log = Objects.requireNonNull(log, "log");
        this.onStart = onStart;
        this.onUpdate = onUpdate;
    }

    // ── Spec 工厂(descriptorLabel = "test") ──────────────────────

    /** 最小件:只记录钩子。 */
    public static Spec spec(List<String> log) {
        return new Spec(log, null, null);
    }

    /** 带 update 行为(交叉观察其他组件状态用)。 */
    public static Spec spec(List<String> log, Consumer<TestComponent> onUpdate) {
        return new Spec(log, null, onUpdate);
    }

    /** 带 start/update 行为。 */
    public static Spec spec(List<String> log, Consumer<TestComponent> onStart,
                            Consumer<TestComponent> onUpdate) {
        return new Spec(log, onStart, onUpdate);
    }

    /** 声明必需依赖(装配期依赖校验测试口径)。 */
    public static Spec requiring(List<String> log, Class<? extends MatchModeComponent> required) {
        Spec spec = new Spec(log, null, null);
        spec.requires(required);
        return spec;
    }

    /** 声明可选依赖(装配期依赖校验测试口径)。 */
    public static Spec optionallyUsing(List<String> log, Class<? extends MatchModeComponent> optional) {
        Spec spec = new Spec(log, null, null);
        spec.requiresOptional(optional);
        return spec;
    }

    /** 假组件描述符。 */
    public static final class Spec extends MatchModeComponent.Specification<TestComponent> {

        private final List<String> log;
        private final Consumer<TestComponent> onStart;
        private final Consumer<TestComponent> onUpdate;

        public Spec(List<String> log, Consumer<TestComponent> onStart, Consumer<TestComponent> onUpdate) {
            super("test");
            this.log = log;
            this.onStart = onStart;
            this.onUpdate = onUpdate;
        }

        @Override
        public TestComponent create(String id, MatchModeInstanceServices svc) {
            return new TestComponent(id, svc, log, onStart, onUpdate);
        }
    }

    // ── 钩子:全部按序记录 ───────────────────────────────────────

    @Override
    public void awake() {
        log.add(id() + ":awake");
    }

    @Override
    public void start() {
        log.add(id() + ":start");
        if (onStart != null) {
            onStart.accept(this);
        }
    }

    @Override
    public void update() {
        log.add(id() + ":update");
        if (onUpdate != null) {
            onUpdate.accept(this);
        }
    }

    @Override
    public void onEvent(MatchModeInstanceEvent event) {
        received.add(event);
        log.add(id() + ":onEvent:" + event.getClass().getSimpleName());
    }

    @Override
    public void onHandoff(Handoff.Builder out) {
        log.add(id() + ":onHandoff");
        out.put(Handoff.Key.of(id(), TOKEN_NAME, String.class), "token-of-" + id());
    }

    @Override
    public void stop() {
        log.add(id() + ":stop");
    }

    // ── 观察包装(把受保护的服务集口开放给测试) ───────────────────

    /** 按类型取同实例内的另一组件(getComponent 的公开包装)。 */
    public <T extends MatchModeComponent> T sibling(Class<T> type) {
        return getComponent(type);
    }

    /** 经实例事件总线发布(测试驱动规则簿等事件消费者)。 */
    public void publish(MatchModeInstanceEvent event) {
        svc().self().events().publish(event);
    }

    /** 登记延迟任务(later 的公开包装;句柄登记到本组件名下)。 */
    public void recordLater(int delayTicks, Runnable task) {
        svc().self().later(delayTicks, task);
    }

    /** 所属会话的只读视图(svc().self().session() 的公开包装;★29 跨级只读断言用)。 */
    public com.hydrokarbs.engine.port.SessionView sessionView() {
        return svc().self().session();
    }

    /** 请求结束(end 的公开包装;容器路径下受幂等护栏)。 */
    public void requestEnd(EndResult result) {
        svc().self().end(result);
    }
}
