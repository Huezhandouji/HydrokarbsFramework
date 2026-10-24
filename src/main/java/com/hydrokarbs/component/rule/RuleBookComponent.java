package com.hydrokarbs.component.rule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.event.MatchModeInstanceEvent;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.map.MapDefinition;

/**
 * 声明式规则簿组件:持有规则列表,事件到达时按声明序求值
 * (施工计划 1.5;06-共享组件 §6.1-#6)。
 *
 * <p>求值链:trigger(事件)→ when(上下文)→ then(上下文);可选件,
 * 不要规则簿的模式不挂。逐条 try/catch 隔离——总线隔离的是订阅者,
 * 规则簿把隔离再下探一层:一个坏规则不会毁掉整局(WARN 后继续下一条)。
 *
 * <p>类注:实例只读视图的来源与 {@code session()} 暂返 null 的登记,
 * 同阶段状态机组件的类注(同一前置缺口,交付报告统一登记)。
 */
public final class RuleBookComponent extends MatchModeComponent {

    private static final Logger LOG = Logger.getLogger(RuleBookComponent.class.getName());

    private final List<Rule> rules;

    /** 装配描述符:声明序 = 求值序。 */
    public static final class Spec extends MatchModeComponent.Specification<RuleBookComponent> {

        private final List<Rule> rules;

        public Spec(List<Rule> rules) {
            super("rulebook");
            if (rules == null || rules.isEmpty()) {
                throw new IllegalArgumentException("规则列表为空:不需要规则的模式不应挂本组件(可选件)");
            }
            this.rules = List.copyOf(rules);
        }

        @Override
        public RuleBookComponent create(String id, MatchModeInstanceServices svc) {
            return new RuleBookComponent(id, svc, rules);
        }
    }

    RuleBookComponent(String id, MatchModeInstanceServices svc, List<Rule> rules) {
        super(id, svc);
        this.rules = rules;
    }

    /** 事件到达(中央总线钩子):按声明序求值,逐条隔离。 */
    @Override
    public void onEvent(MatchModeInstanceEvent event) {
        Objects.requireNonNull(event, "event");
        RuleContext ctx = null;     // 惰性构造:trigger 不匹配的规则不付上下文构造成本
        for (Rule rule : rules) {
            try {
                if (!rule.trigger().test(event)) {
                    continue;
                }
                if (ctx == null) {
                    ctx = new EventContext(event);
                }
                if (rule.when().test(ctx)) {
                    rule.then().accept(ctx);
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "规则求值异常(已隔离,继续下一条): 规则 " + rule.id()
                        + ",事件类型 " + event.getClass().getName(), t);
            }
        }
    }

    /** 服务集背后的只读视图 + 当前事件(规则求值上下文)。 */
    private final class EventContext implements RuleContext {

        private final MatchModeInstanceEvent event;

        EventContext(MatchModeInstanceEvent event) {
            this.event = event;
        }

        @Override
        public MatchModeInstanceEvent event() {
            return event;
        }

        @Override
        public <T extends MatchModeComponent> T component(Class<T> type) {
            return svc().components().get(type);
        }

        @Override
        public <T> Optional<T> attr(AttributeBag.Key<T> key) {
            return svc().self().attrs().get(key);
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
        public Session session() {
            return null;    // 自面暂无会话读口(见类注),补齐后改为委托
        }
    }
}
