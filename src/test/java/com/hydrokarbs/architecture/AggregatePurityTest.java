package com.hydrokarbs.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.mode.MatchMode;

/**
 * 聚合根零行为(★22;02-核心概念 §2.8-决断二;14-陷阱清单 §14.23)。
 *
 * <p>反射冻结 {@link MatchMode} 的公开方法集:断言「恰好等于」九个而非「包含」——
 * 多一个方法即红。{@code MatchMode} 上出现任何「顺手的方法」都意味着有一段行为
 * 找不到组件之家;失败时的唯一正确动作是把行为还给组件(给描述符加构造数据或新造
 * 组件),不是改断言。
 *
 * <p>只扫 {@code MatchMode} 自身:{@code getDeclaredMethods()} 不含嵌套类的声明
 * (Builder/ComponentEntry 是嵌套类,各有自由,不在冻结面内)。
 */
class AggregatePurityTest {

    /** §2.8-决断二的机械清单:九个方法,一个不多、一个不少。 */
    private static final Set<String> FROZEN_PUBLIC_METHODS = Set.of(
            "verifyDependencies",
            "createInstance",
            "id",
            "displayName",
            "description",
            "icon",
            "mapTags",
            "meta",
            "components");

    @Test
    void publicMethodSetIsExactlyTheNineFrozenOnes() {
        Set<String> actual = Arrays.stream(MatchMode.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getDeclaringClass() == MatchMode.class)
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(FROZEN_PUBLIC_METHODS, actual,
                () -> "MatchMode 公开方法集必须恰好等于九个(§2.8-决断二;★22)。"
                        + "期望=" + FROZEN_PUBLIC_METHODS + " 实际=" + actual
                        + ";多了 = 聚合根长出行为(把行为还给组件),少了 = 契约被删。");
    }

    @Test
    void aggregateRootIsFinalWithNoPublicConstructor() {
        assertTrue(Modifier.isFinal(MatchMode.class.getModifiers()),
                "MatchMode 必须 final(聚合根不被继承改写)");
        assertTrue(Arrays.stream(MatchMode.class.getDeclaredConstructors())
                        .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())),
                "MatchMode 不得有 public 构造器(唯一装配入口是 Builder)");
    }
}
