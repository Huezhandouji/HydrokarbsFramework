package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.service.ServiceLifecycle;
import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;
import com.hydrokarbs.engine.session.CapabilityRegistry;

/**
 * ★37 失败即回滚(1.6「测试与验收」表;18 号修正案 §7)。
 *
 * <p>断言要点:第 N 个工厂/start 抛 → 已启动服务逆序 stop 恰一次;
 * {@code get} 转不可用。观察口径:start/stop 各记一行,断言日志序列与 get 结果。
 */
class ServiceRegistryRollbackTest {

    /** 记录型服务基类:start 可配置抛出(先记录再抛,以观察「尝试恰一次」)。 */
    static class Recording implements ServiceLifecycle {

        final String id;
        final List<String> log;
        final boolean boomOnStart;

        protected Recording(String id, List<String> log) {
            this(id, log, false);
        }

        protected Recording(String id, List<String> log, boolean boomOnStart) {
            this.id = id;
            this.log = log;
            this.boomOnStart = boomOnStart;
        }

        @Override
        public void start() {
            log.add("start:" + id);
            if (boomOnStart) {
                throw new IllegalStateException("boom-start:" + id);
            }
        }

        @Override
        public void stop() {
            log.add("stop:" + id);
        }
    }

    static final class Aaa extends Recording {
        Aaa(String id, List<String> log) {
            super(id, log);
        }
    }

    static final class Bbb extends Recording {
        Bbb(String id, List<String> log) {
            super(id, log);
        }

        Bbb(String id, List<String> log, boolean boomOnStart) {
            super(id, log, boomOnStart);
        }
    }

    /** 工厂恒抛的服务类型(无需实体)。 */
    static final class Ccc {
    }

    /** 第 N 个工厂抛 → 已启动服务逆序 stop 恰一次;get 转不可用;原异常上抛。 */
    @Test
    void factoryFailureRollsBackStartedServicesInReverse() {
        ServiceRegistry registry = new ServiceRegistry(new CapabilityRegistry());
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("a", Aaa.class, env -> new Aaa("a", log)).build());
        registry.install(ServiceSpec.of("b", Bbb.class, env -> new Bbb("b", log)).build());
        registry.install(ServiceSpec.of("c", Ccc.class, env -> {
            throw new IllegalStateException("boom-factory-c");
        }).build());

        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::seal);
        assertEquals("boom-factory-c", ex.getMessage(), "工厂的原始异常须原样上抛(不吞不换)");

        assertEquals(List.of("start:a", "start:b", "stop:b", "stop:a"), log,
                "已启动服务逆序 stop,各恰一次;c 未启动故不在回滚名单");
        assertTrue(registry.get(Aaa.class).isEmpty(), "seal 失败后 get 转不可用(★37)");
        assertTrue(registry.get(Bbb.class).isEmpty(), "seal 失败后 get 转不可用(★37)");
        assertTrue(registry.get(Ccc.class).isEmpty());
    }

    /** start 抛 → 已启动者逆序 stop 恰一次;启动失败者不被 stop(未成功启动,对称口径)。 */
    @Test
    void startFailureRollsBackOnlyStartedServices() {
        ServiceRegistry registry = new ServiceRegistry(new CapabilityRegistry());
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("a", Aaa.class, env -> new Aaa("a", log)).build());
        registry.install(ServiceSpec.of("b", Bbb.class, env -> new Bbb("b", log, true)).build());
        registry.install(ServiceSpec.of("c", Ccc.class, env -> {
            throw new IllegalStateException("never-reached");
        }).build());

        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::seal);
        assertEquals("boom-start:b", ex.getMessage(), "start 的原始异常须原样上抛");

        assertEquals(List.of("start:a", "start:b", "stop:a"), log,
                "仅已启动的 a 逆序 stop;b start 失败不被 stop;c 根本未创建");
        assertTrue(registry.get(Aaa.class).isEmpty());
    }

    /** 回滚后注册表终态封闭:install/seal 均拒。 */
    @Test
    void rolledBackRegistryIsClosed() {
        ServiceRegistry registry = new ServiceRegistry(new CapabilityRegistry());
        registry.install(ServiceSpec.of("a", Aaa.class, env -> {
            throw new IllegalStateException("boom");
        }).build());
        assertThrows(IllegalStateException.class, registry::seal);
        assertThrows(IllegalStateException.class,
                () -> registry.install(ServiceSpec.of("late", Bbb.class, env -> new Bbb("late", List.of())).build()));
        assertThrows(IllegalStateException.class, registry::seal);
    }
}
