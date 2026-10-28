package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;

/**
 * ★34 注册查重响亮失败(1.6「测试与验收」表;18 号修正案 §7)。
 *
 * <p>断言要点:重复 id / 重复类型 → 抛且点名先到者;seal/shutdown 后 install 抛。
 * 全部离线(零 Bukkit import、不碰 Bukkit 静态,平台原语位置不触及)。
 */
class ServiceRegistryDupTest {

    /** 测试服务类型甲。 */
    static final class Alpha {
    }

    /** 测试服务类型乙。 */
    static final class Beta {
    }

    private static ServiceRegistry newRegistry() {
        return new ServiceRegistry();
    }

    /** 重复 id → 抛,消息点名先到者(id + 先到者类型,两个同 id 声明靠类型区分)。 */
    @Test
    void duplicateIdFailsLoudlyNamingFirstArrival() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("dup", Alpha.class, env -> new Alpha()).build());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.install(ServiceSpec.of("dup", Beta.class, env -> new Beta()).build()));
        assertTrue(ex.getMessage().contains("dup"),
                "消息须点名重复 id,实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(Alpha.class.getName()),
                "消息须点名先到者(其主类型),实际: " + ex.getMessage());
    }

    /** 重复类型 → 抛,消息点名先到者(其 id)。 */
    @Test
    void duplicateTypeFailsLoudlyNamingFirstArrival() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("first", Alpha.class, env -> new Alpha()).build());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.install(ServiceSpec.of("second", Alpha.class, env -> new Alpha()).build()));
        assertTrue(ex.getMessage().contains("first"),
                "消息须点名先到者 id,实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(Alpha.class.getName()),
                "消息须点名冲突类型,实际: " + ex.getMessage());
    }

    /** seal 之后 install 抛(两阶段契约:seal 后注册表封闭)。 */
    @Test
    void installAfterSealThrows() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("only", Alpha.class, env -> new Alpha()).build());
        registry.seal();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.install(ServiceSpec.of("late", Beta.class, env -> new Beta()).build()));
        assertTrue(ex.getMessage().contains("late"),
                "消息须点名被拒的声明,实际: " + ex.getMessage());
    }

    /** shutdown 之后 install 抛。 */
    @Test
    void installAfterShutdownThrows() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("only", Alpha.class, env -> new Alpha()).build());
        registry.seal();
        registry.shutdown();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.install(ServiceSpec.of("late", Beta.class, env -> new Beta()).build()));
        assertTrue(ex.getMessage().contains("late"),
                "消息须点名被拒的声明,实际: " + ex.getMessage());
    }
}
