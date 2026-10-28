package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.port.ConfigView;
import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;

/**
 * ★39 ServiceEnv 语义(1.6「测试与验收」表;18 号修正案 §7)。
 *
 * <p>断言要点:{@code dep} 缺席抛人话异常;{@code depOpt} empty;
 * {@code cfg()} 惰性(换底层读新值)。cfg 假实现 = Map 背后(镜像 1.2
 * {@code MatchModeConfig} 的做法);平台原语位置传 null(离线规避 Bukkit 类型,
 * 测试零 Bukkit import)。
 */
class ServiceEnvTest {

    /** 从不注册的缺席类型。 */
    static final class Absent {
    }

    /** 依赖提供者。 */
    static final class Provider {
    }

    /** 观察者服务(工厂里捕获 env 行为)。 */
    static final class Probe {
    }

    /** dep 缺席(未声明、未装配)→ IllegalStateException,消息点名类型(人话)。 */
    @Test
    void depAbsenceFailsLoudlyWithTypeName() {
        ServiceRegistry registry = new ServiceRegistry();
        AtomicReference<Throwable> caught = new AtomicReference<>();
        registry.install(ServiceSpec.of("probe", Probe.class, env -> {
            try {
                env.dep(Absent.class);
            } catch (Throwable t) {
                caught.set(t);
            }
            return new Probe();
        }).build());
        registry.seal();

        assertNotNull(caught.get(), "dep 取不到必须响亮抛,不得返回 null");
        Throwable t = caught.get();
        assertInstanceOf(IllegalStateException.class, t,
                "dep 缺席的异常类型: " + t.getClass().getName());
        assertTrue(t.getMessage().contains(Absent.class.getSimpleName()),
                "异常消息须人话点名缺席类型,实际: " + t.getMessage());
    }

    /** depOpt 缺席 → empty(可选依赖语义:缺席不抛)。 */
    @Test
    void depOptAbsenceYieldsEmpty() {
        ServiceRegistry registry = new ServiceRegistry();
        AtomicReference<Optional<Absent>> seen = new AtomicReference<>();
        registry.install(ServiceSpec.of("probe", Probe.class, env -> {
            seen.set(env.depOpt(Absent.class));
            return new Probe();
        }).build());
        registry.seal();
        assertTrue(seen.get().isEmpty(), "depOpt 缺席须返回 empty");
    }

    /** dep 声明且在场 → 返回已就绪实例(拓扑序保证;dep 的正面语义)。 */
    @Test
    void depDeclaredReturnsReadyInstance() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.install(ServiceSpec.of("provider", Provider.class, env -> new Provider()).build());
        AtomicReference<Provider> seen = new AtomicReference<>();
        registry.install(ServiceSpec.of("probe", Probe.class, env -> {
            seen.set(env.dep(Provider.class));
            return new Probe();
        }).requires(Provider.class).build());
        registry.seal();
        assertNotNull(seen.get());
        assertSame(registry.get(Provider.class).orElseThrow(), seen.get(),
                "dep 取到的必须是注册表装配的同一实例");
    }

    /** cfg() 惰性:同一 ConfigView 每次读取穿透到底层,换底层数据后自动读新值。 */
    @Test
    void cfgIsLazyAndReadsThroughUnderlyingChange() {
        Map<String, Integer> backing = new HashMap<>(Map.of("sweep-interval", 10));
        ConfigView view = new ConfigView() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(String path, T fallback) {
                return backing.containsKey(path) ? (T) backing.get(path) : fallback;
            }
        };
        ServiceRegistry registry = new ServiceRegistry(null, view);
        AtomicReference<ConfigView> seen = new AtomicReference<>();
        registry.install(ServiceSpec.of("probe", Probe.class, env -> {
            seen.set(env.cfg());
            return new Probe();
        }).build());
        registry.seal();

        assertEquals(10, seen.get().get("sweep-interval", 0));
        backing.put("sweep-interval", 30);                 // 换底层(服主 /reload 的等价最小模型)
        assertEquals(30, seen.get().get("sweep-interval", 0),
                "cfg() 不得缓存首次取值——底层换数据后必须读到新值(★39 惰性语义)");
        assertEquals(7, seen.get().get("unknown-path", 7), "缺路径回落 fallback");
    }
}
