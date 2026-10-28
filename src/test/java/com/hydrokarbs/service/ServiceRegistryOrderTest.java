package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.service.ServiceLifecycle;
import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;

/**
 * ★36 拓扑序 + 声明序稳定(1.6「测试与验收」表;18 号修正案 §7)。
 *
 * <p>断言要点:乱序 install → 启动顺序为拓扑序;同层按声明序。
 * 观察口径:各服务在 start() 里向共享日志记一行,断言日志序列。
 * 每个服务类型各立一类(注册表按类型查重),行为继承共通记录基类。
 */
class ServiceRegistryOrderTest {

    /** 记录型服务基类:start/stop 各记一行。 */
    static class Recording implements ServiceLifecycle {

        final String id;
        final List<String> log;

        protected Recording(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override
        public void start() {
            log.add("start:" + id);
        }

        @Override
        public void stop() {
            log.add("stop:" + id);
        }
    }

    static final class Layer1 extends Recording {
        Layer1(String id, List<String> log) {
            super(id, log);
        }
    }

    static final class Layer2 extends Recording {
        Layer2(String id, List<String> log) {
            super(id, log);
        }
    }

    static final class StandaloneX extends Recording {
        StandaloneX(String id, List<String> log) {
            super(id, log);
        }
    }

    static final class StandaloneY extends Recording {
        StandaloneY(String id, List<String> log) {
            super(id, log);
        }
    }

    static final class StandaloneZ extends Recording {
        StandaloneZ(String id, List<String> log) {
            super(id, log);
        }
    }

    /** 乱序 install(消费者在前、提供者在后)→ 启动序为拓扑序(提供者先启动)。 */
    @Test
    void declarationOrderYieldsToTopologicalOrder() {
        ServiceRegistry registry = new ServiceRegistry();
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("consumer", Layer2.class, env -> new Layer2("consumer", log))
                .requires(Layer1.class)
                .build());
        registry.install(ServiceSpec.of("provider", Layer1.class, env -> new Layer1("provider", log))
                .build());
        registry.seal();
        assertEquals(List.of("start:provider", "start:consumer"), log,
                "启动顺序必须是拓扑序(被依赖者先启动),与声明顺序无关");
    }

    /** 无依赖关系的同层服务按声明序启动。 */
    @Test
    void sameLayerKeepsDeclarationOrder() {
        ServiceRegistry registry = new ServiceRegistry();
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("z-first", StandaloneX.class,
                env -> new StandaloneX("z-first", log)).build());
        registry.install(ServiceSpec.of("a-second", StandaloneY.class,
                env -> new StandaloneY("a-second", log)).build());
        registry.install(ServiceSpec.of("m-third", StandaloneZ.class,
                env -> new StandaloneZ("m-third", log)).build());
        registry.seal();
        assertEquals(List.of("start:z-first", "start:a-second", "start:m-third"), log,
                "同层(无相互依赖)服务的启动序 = 声明序");
    }

    /** 混合:依赖对间按拓扑序,可并行层内按声明序(逐轮就绪序可精确推演)。 */
    @Test
    void mixedGraphOrdersTopologicallyThenByDeclaration() {
        ServiceRegistry registry = new ServiceRegistry();
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("l2", Layer2.class, env -> new Layer2("l2", log))
                .requires(Layer1.class)
                .build());
        registry.install(ServiceSpec.of("x", StandaloneX.class, env -> new StandaloneX("x", log)).build());
        registry.install(ServiceSpec.of("l1", Layer1.class, env -> new Layer1("l1", log)).build());
        registry.install(ServiceSpec.of("y", StandaloneY.class, env -> new StandaloneY("y", log)).build());
        registry.seal();
        // 声明序 [l2, x, l1, y];l2 依赖 l1。逐轮取「声明序最前的就绪者」:
        // 第 1 轮 l2 阻塞 → 取 x;第 2 轮候选 {l1, y} → 取 l1(l1 就绪解除 l2 阻塞);
        // 第 3 轮候选 {l2, y} → 取 l2;第 4 轮取 y。
        assertEquals(List.of("start:x", "start:l1", "start:l2", "start:y"), log,
                "拓扑序优先;同层(同时就绪)按声明序");
    }
}
