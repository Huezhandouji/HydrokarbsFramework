package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.service.ServiceLifecycle;
import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;

/**
 * ★38 关停逆序幂等(1.6「测试与验收」表;18 号修正案 §7)。
 *
 * <p>断言要点:shutdown 连调 3 次 stop 恰一次;stop 异常不中断其余。
 * ★v1.7(25 号修正案):原能力标签注册/摘除命题(capabilityTagsRegisteredOnSealAndRemovedOnShutdown)
 * 随能力标签机制全链退场删除,shutdown 契约自此不含标签步骤。
 */
class ServiceRegistryShutdownTest {

    /** 记录型服务基类:stop 可配置抛出(先记录再抛,以观察「尝试恰一次」)。 */
    static class Recording implements ServiceLifecycle {

        final String id;
        final List<String> log;
        final boolean boomOnStop;

        protected Recording(String id, List<String> log) {
            this(id, log, false);
        }

        protected Recording(String id, List<String> log, boolean boomOnStop) {
            this.id = id;
            this.log = log;
            this.boomOnStop = boomOnStop;
        }

        @Override
        public void start() {
            log.add("start:" + id);
        }

        @Override
        public void stop() {
            log.add("stop:" + id);
            if (boomOnStop) {
                throw new IllegalStateException("boom-stop:" + id);
            }
        }
    }

    static final class Aaa extends Recording {
        Aaa(String id, List<String> log) {
            super(id, log);
        }

        Aaa(String id, List<String> log, boolean boomOnStop) {
            super(id, log, boomOnStop);
        }
    }

    static final class Bbb extends Recording {
        Bbb(String id, List<String> log) {
            super(id, log);
        }

        Bbb(String id, List<String> log, boolean boomOnStop) {
            super(id, log, boomOnStop);
        }
    }

    static final class Ccc extends Recording {
        Ccc(String id, List<String> log) {
            super(id, log);
        }
    }

    /** shutdown 连调 3 次:每个服务的 stop 恰执行一次,顺序与启动序相反。 */
    @Test
    void repeatedShutdownStopsEachServiceExactlyOnceInReverse() {
        ServiceRegistry registry = new ServiceRegistry();
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("a", Aaa.class, env -> new Aaa("a", log)).build());
        registry.install(ServiceSpec.of("b", Bbb.class, env -> new Bbb("b", log)).build());
        registry.install(ServiceSpec.of("c", Ccc.class, env -> new Ccc("c", log)).build());
        registry.seal();

        registry.shutdown();
        registry.shutdown();
        registry.shutdown();

        assertEquals(List.of("start:a", "start:b", "start:c", "stop:c", "stop:b", "stop:a"), log,
                "关停逆序;连调 3 次每个 stop 恰执行一次(幂等)");
    }

    /** 某服务 stop 抛异常:不中断其余服务的关停,shutdown 本身不外抛。 */
    @Test
    void stopFailureDoesNotBreakRemainingShutdown() {
        ServiceRegistry registry = new ServiceRegistry();
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("boom", Aaa.class, env -> new Aaa("boom", log, true)).build());
        registry.install(ServiceSpec.of("ok", Bbb.class, env -> new Bbb("ok", log)).build());
        registry.seal();

        assertDoesNotThrow(registry::shutdown, "单个 stop 异常须被隔离,不得从 shutdown 逃逸");
        assertEquals(List.of("start:boom", "start:ok", "stop:ok", "stop:boom"), log,
                "逆序关停:ok 先停(正常),boom 后停(记录一次后抛,不中断——它是最后一个)");
    }

    /** stop 异常在中间位也不中断其余(boom 放中段)。 */
    @Test
    void midStopFailureDoesNotBreakTheRest() {
        ServiceRegistry registry = new ServiceRegistry();
        List<String> log = new ArrayList<>();
        registry.install(ServiceSpec.of("a", Aaa.class, env -> new Aaa("a", log)).build());
        registry.install(ServiceSpec.of("boom", Bbb.class, env -> new Bbb("boom", log, true)).build());
        registry.install(ServiceSpec.of("c", Ccc.class, env -> new Ccc("c", log)).build());
        registry.seal();

        assertDoesNotThrow(registry::shutdown);
        assertEquals(List.of("start:a", "start:boom", "start:c", "stop:c", "stop:boom", "stop:a"), log,
                "中段的 stop 异常不阻断后续关停(c→boom(抛)→a 全部尝试恰一次)");
    }
}
