package com.hydrokarbs.testkit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.event.MatchModeInstanceEvent;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.EndResult;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.map.MapDefinition;

/**
 * 切换链路探针组件(2.3 测试):六钩子按序记录 + 可注入的失败点
 * (造件抛/唤醒抛/生效抛)、地图适配检查问题清单、交接写出、按拍请求结束。
 * 与 {@link TestComponent} 分工:那件面向容器派发序,本件面向切换 A/B 段的失败注入。
 * 行为配置在描述符上,组件构造期捕获同一描述符引用(同一 Spec 跨模式共享时
 * 各自捕获互不串改——快照纪律与生产描述符一致)。
 */
public final class ProbeComponent extends MatchModeComponent {

    /** 交接键名(namespace = 组件实例 id,键随主人)。 */
    public static final String TOKEN_NAME = "carry";

    private final Spec spec;
    private final List<String> log;
    private int updates;

    private ProbeComponent(String id, MatchModeInstanceServices svc, Spec spec) {
        super(id, svc);
        this.spec = spec;
        this.log = spec.log;
    }

    /** 探针描述符(失败点与行为全可配;默认只记录钩子)。 */
    public static final class Spec extends MatchModeComponent.Specification<ProbeComponent> {

        private final List<String> log;
        private boolean createThrows;
        private String createMessage = "probe-create-boom";
        private boolean awakeThrows;
        private String awakeMessage = "probe-awake-boom";
        private boolean startThrows;
        private String startMessage = "probe-start-boom";
        private List<String> mapCheckIssues = List.of();
        private String handoffValue;
        private int endAfterUpdates = -1;
        private EndResult endResult = EndResult.nowhere();
        private Consumer<ProbeComponent> onStart;

        public Spec(List<String> log) {
            super("probe");
            this.log = Objects.requireNonNull(log, "log");
        }

        /** 造件抛(B 段判据①:装配抛 = 直接回退)。 */
        public Spec failOnCreate(String message) {
            this.createThrows = true;
            this.createMessage = message;
            return this;
        }

        /** 唤醒抛(B 段判据①:装配抛 = 直接回退)。 */
        public Spec failOnAwake(String message) {
            this.awakeThrows = true;
            this.awakeMessage = message;
            return this;
        }

        /** 生效抛(B 段判据②:生效抛 = 先全停再回退)。 */
        public Spec failOnStart(String message) {
            this.startThrows = true;
            this.startMessage = message;
            return this;
        }

        /** 地图适配检查返回问题清单(A3 失败注入)。 */
        public Spec mapCheckIssue(String issue) {
            this.mapCheckIssues = List.of(issue);
            return this;
        }

        /** 交接出局时写出携带值(B1/★18 链路)。 */
        public Spec handoffValue(String value) {
            this.handoffValue = value;
            return this;
        }

        /** 第 N 次 update 时经端口请求结束(自动切换驱动注入;N&lt;0 = 不请求)。 */
        public Spec endAfter(int updates, EndResult result) {
            this.endAfterUpdates = updates;
            this.endResult = result;
            return this;
        }

        /** 生效回调(观察/断言辅助)。 */
        public Spec onStart(Consumer<ProbeComponent> onStart) {
            this.onStart = onStart;
            return this;
        }

        @Override
        public ProbeComponent create(String id, MatchModeInstanceServices svc) {
            if (createThrows) {
                log.add(id + ":create-throws");
                throw new IllegalStateException(createMessage);
            }
            return new ProbeComponent(id, svc, this);
        }

        @Override
        public List<String> checkMap(MapDefinition map, MatchModeConfig cfg) {
            return mapCheckIssues;
        }
    }

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
        if (spec.onStart != null) {
            spec.onStart.accept(this);
        }
    }

    @Override
    public void update() {
        log.add(id() + ":update");
        updates++;
        if (spec.endAfterUpdates >= 0 && updates >= spec.endAfterUpdates) {
            log.add(id() + ":end-request");
            svc().self().end(spec.endResult);
        }
    }

    @Override
    public void onEvent(MatchModeInstanceEvent event) {
        log.add(id() + ":onEvent:" + event.getClass().getSimpleName());
    }

    @Override
    public void onHandoff(Handoff.Builder out) {
        log.add(id() + ":onHandoff");
        if (spec.handoffValue != null) {
            out.put(Handoff.Key.of(id(), TOKEN_NAME, String.class), spec.handoffValue);
        }
    }

    @Override
    public void stop() {
        log.add(id() + ":stop");
    }

    /** 已经历的 update 拍数(断言辅助)。 */
    public int updates() {
        return updates;
    }
}
