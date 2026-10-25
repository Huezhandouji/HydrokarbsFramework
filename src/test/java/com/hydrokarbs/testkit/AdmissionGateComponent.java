package com.hydrokarbs.testkit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.port.JoinAdmission;
import com.hydrokarbs.engine.port.JoinContext;
import com.hydrokarbs.engine.port.JoinDecision;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;

/**
 * 入场裁决假组件(★v3.2 端口的测试件):实现 {@link JoinAdmission} 的实例组件,
 * 裁决由注入的函数产出(按上下文/玩家返回 ADMIT/SPECTATE/REJECT)。
 * 用于验证两个触发点(初始摆放/运行中加入)的裁决链路。
 */
public final class AdmissionGateComponent extends MatchModeComponent implements JoinAdmission {

    private final Function<UUID, JoinDecision> policy;
    /** 最近一次收到的触发点(断言辅助)。 */
    private JoinContext.Trigger lastTrigger;

    private AdmissionGateComponent(String id, MatchModeInstanceServices svc,
                                   Function<UUID, JoinDecision> policy) {
        super(id, svc);
        this.policy = policy;
    }

    /** 裁决描述符:policy 按玩家产出裁决。 */
    public static final class Spec extends MatchModeComponent.Specification<AdmissionGateComponent> {

        private final Function<UUID, JoinDecision> policy;

        public Spec(Function<UUID, JoinDecision> policy) {
            super("admission-gate");
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        @Override
        public AdmissionGateComponent create(String id, MatchModeInstanceServices svc) {
            return new AdmissionGateComponent(id, svc, policy);
        }
    }

    @Override
    public JoinDecision admit(UUID player, JoinContext ctx) {
        lastTrigger = ctx.trigger();
        return policy.apply(player);
    }

    /** 最近一次裁决收到的触发点。 */
    public JoinContext.Trigger lastTrigger() {
        return lastTrigger;
    }
}
