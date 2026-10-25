package com.hydrokarbs.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.port.SessionServices;

/**
 * 会话组件 svc 两成员封闭(★v4/2.6;02-核心概念 §2.5.1;16 §16.2 修订注;
 * 12-代码骨架 §12.1 测试树;★40 会话侧「保持 2 成员」验收判据)。
 *
 * <p>反射冻结 {@link SessionServices} 的 record 组件集:恰为
 * {@code (self, components)} 且顺序不变——与实例侧
 * {@link MatchModeInstanceServicesMembersTest}(3 成员)同款写法。
 * 「第三个成员」(顺手转发显示/持久化/领域便利口)直接红:给服务集加成员
 * 永远是错误答案(§2.8-决断五);想要领域能力就 {@code svc().components().get(...)}
 * 拿组件,或走 {@code self()} 上的端口组。
 */
class SessionServicesMembersTest {

    @Test
    void recordComponentsAreExactlySelfComponentsInOrder() {
        RecordComponent[] components = SessionServices.class.getRecordComponents();
        assertNotNull(components, "SessionServices 必须仍是 record(成员冻结以 record 组件为准)");
        assertEquals(
                List.of("self", "components"),
                Arrays.stream(components).map(RecordComponent::getName).toList(),
                "会话 svc 成员封闭(2):record 组件必须恰为 (self, components),"
                        + "名称与顺序都不得增删移——第三个成员即红");
    }
}
