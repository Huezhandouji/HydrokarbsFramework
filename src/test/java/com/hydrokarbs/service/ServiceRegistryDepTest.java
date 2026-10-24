package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.service.ServiceDependencyException;
import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;
import com.hydrokarbs.engine.session.CapabilityRegistry;

/**
 * ★35 装配期依赖校验(1.6「测试与验收」表;18 号修正案 §7)。
 *
 * <p>断言要点:缺必需依赖 → {@link ServiceDependencyException} 点名双方;
 * 依赖环 → 点名环上服务;{@code requiresOptional} 缺席不报错、{@code depOpt} empty。
 */
class ServiceRegistryDepTest {

    /** 缺席的依赖类型(从不 install)。 */
    static final class Missing {
    }

    static final class Provider {
    }

    static final class ConsumerA {
    }

    static final class ConsumerB {
    }

    /** 可选依赖消费者(工厂里记录 depOpt 结果)。 */
    static final class OptionalConsumer {
    }

    private static ServiceRegistry newRegistry() {
        return new ServiceRegistry(new CapabilityRegistry());
    }

    /** 缺必需依赖 → ServiceDependencyException,消息点名双方(声明方 + 缺失类型)。 */
    @Test
    void missingRequiredDependencyNamesBothSides() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("needy", ConsumerA.class, env -> new ConsumerA())
                .requires(Missing.class)
                .build());
        ServiceDependencyException ex = assertThrows(ServiceDependencyException.class,
                registry::seal);
        assertTrue(ex.getMessage().contains("needy"),
                "消息须点名声明方服务,实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(Missing.class.getName()),
                "消息须点名缺失的依赖类型(双方),实际: " + ex.getMessage());
    }

    /** 依赖环 → ServiceDependencyException,消息点名环上全部服务。 */
    @Test
    void dependencyCycleNamesCycleMembers() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("loop-a", ConsumerA.class, env -> new ConsumerA())
                .requires(ConsumerB.class)
                .build());
        registry.install(ServiceSpec.of("loop-b", ConsumerB.class, env -> new ConsumerB())
                .requires(ConsumerA.class)
                .build());
        ServiceDependencyException ex = assertThrows(ServiceDependencyException.class,
                registry::seal);
        assertTrue(ex.getMessage().contains("loop-a") && ex.getMessage().contains("loop-b"),
                "消息须点名环上全部服务,实际: " + ex.getMessage());
    }

    /** 自需求(声明自己主类型的依赖)按环点名。 */
    @Test
    void selfRequirementIsReportedAsCycle() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("self-loop", ConsumerA.class, env -> new ConsumerA())
                .requires(ConsumerA.class)
                .build());
        ServiceDependencyException ex = assertThrows(ServiceDependencyException.class,
                registry::seal);
        assertTrue(ex.getMessage().contains("self-loop"),
                "消息须点名环上服务(自需求),实际: " + ex.getMessage());
    }

    /** requiresOptional 缺席:seal 不报错;运行期 depOpt 返回 empty 不抛。 */
    @Test
    void optionalAbsenceIsToleratedAndDepOptIsEmpty() {
        ServiceRegistry registry = newRegistry();
        AtomicReference<Optional<Missing>> seen = new AtomicReference<>();
        registry.install(ServiceSpec.of("relaxed", OptionalConsumer.class, env -> {
            seen.set(env.depOpt(Missing.class));
            return new OptionalConsumer();
        }).requiresOptional(Missing.class).build());
        assertDoesNotThrow(registry::seal, "可选依赖缺席不得让 seal 失败");
        assertTrue(seen.get().isEmpty(), "depOpt 缺席须返回 empty 而非抛异常");
    }

    /** 健全依赖(provider 声明在 consumer 之后)装配成功——★36 的前置正例。 */
    @Test
    void declaredDependencyAssemblySucceeds() {
        ServiceRegistry registry = newRegistry();
        registry.install(ServiceSpec.of("consumer", ConsumerA.class, env -> new ConsumerA())
                .requires(Provider.class)
                .build());
        registry.install(ServiceSpec.of("provider", Provider.class, env -> new Provider())
                .build());
        assertDoesNotThrow(registry::seal);
        assertTrue(registry.get(Provider.class).isPresent());
    }
}
