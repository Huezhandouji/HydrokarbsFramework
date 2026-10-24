package com.hydrokarbs.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.port.MatchModeInstanceServices;

/**
 * svc 三成员封闭(★25;02-核心概念 §2.5;14-陷阱清单 §14.25)。
 *
 * <p>反射冻结 {@link MatchModeInstanceServices} 的 record 组件集:恰为
 * {@code (self, components, modeInfo)} 且顺序不变。「第四个、第五个成员」
 * (顺手转发显示/持久化/领域便利口)直接红——服务集变成上帝上下文时,组件对
 * 框架的全部依赖面失控。想要领域能力就 {@code svc().components().get(...)}
 * 拿组件;给服务集加成员永远是错误答案(§2.8-决断五),新增成员须过设计审查。
 */
class MatchModeInstanceServicesMembersTest {

    @Test
    void recordComponentsAreExactlySelfComponentsModeInfoInOrder() {
        RecordComponent[] components = MatchModeInstanceServices.class.getRecordComponents();
        assertNotNull(components, "MatchModeInstanceServices 必须仍是 record(成员冻结以 record 组件为准)");
        assertEquals(
                List.of("self", "components", "modeInfo"),
                Arrays.stream(components).map(RecordComponent::getName).toList(),
                "svc 成员封闭(★25):record 组件必须恰为 (self, components, modeInfo),"
                        + "名称与顺序都不得增删移——第四个成员即红");
    }
}
