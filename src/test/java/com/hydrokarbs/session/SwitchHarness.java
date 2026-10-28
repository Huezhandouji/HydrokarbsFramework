package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.hydrokarbs.engine.EngineTicker;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.ModeLookup;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionState;
import com.hydrokarbs.engine.session.SessionServiceImpl;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.map.MapRegistry;
import com.hydrokarbs.testkit.FakeWorldPort;
import com.hydrokarbs.testkit.MapTestKit;
import com.hydrokarbs.testkit.OfflineAssembly;

/**
 * 切换链路离线装配(2.3 测试基座):真注册表(假 provider 包装预造模式)+ 真地图目录 +
 * 世界假件 + 真会话服务(主线程直通/传送函数记录)+ 真节拍器(不 start,直调驱动)。
 * 同包放置:会话服务的包级写口(挂起计划/状态迁移)在本包测试可直接观测。
 */
final class SwitchHarness {

    /** 共享测试标签(模式与地图都带它,零标签过滤干扰)。 */
    static final Set<String> TAG = Set.of("common");

    final MatchModeRegistry registry = new MatchModeRegistry(id -> OfflineAssembly.cfg());
    final MapRegistry maps;
    final FakeWorldPort worlds;
    final List<Collection<UUID>> relocations = new ArrayList<>();       // 解散传送记录
    final List<Collection<UUID>> cleanups = new ArrayList<>();          // 兜底清态记录
    final List<String> logLines = new ArrayList<>();                    // 服务日志(WARN/SEVERE 断言)
    final SessionServiceImpl service;
    final EngineTicker ticker;

    SwitchHarness(String fallbackModeId, MatchMode... modes) {
        this(FakeWorldPort.immediate(), fallbackModeId, null, modes);
    }

    /** 带自定义平台口的装配(2.6:会话侧 later 句柄登记/回收断言)。 */
    static SwitchHarness withPlatform(com.hydrokarbs.engine.port.PlatformPort platform,
                                      String fallbackModeId, MatchMode... modes) {
        return new SwitchHarness(FakeWorldPort.immediate(), fallbackModeId, null, platform, modes);
    }

    /** 自定义世界端口装配(A6 失败/超时注入)。 */
    SwitchHarness(FakeWorldPort worlds, String fallbackModeId, MatchMode... modes) {
        this(worlds, fallbackModeId, null, modes);
    }

    /** 带自定义模式查询口的装配(A5 依赖失败注入:lookup 返回坏模式)。 */
    SwitchHarness(String fallbackModeId, ModeLookup lookupOverride, MatchMode... modes) {
        this(FakeWorldPort.immediate(), fallbackModeId, lookupOverride, modes);
    }

    private SwitchHarness(FakeWorldPort worlds, String fallbackModeId, ModeLookup lookupOverride,
                          MatchMode... modes) {
        this(worlds, fallbackModeId, lookupOverride, new OfflineAssembly.FakePlatform(), modes);
    }

    private SwitchHarness(FakeWorldPort worlds, String fallbackModeId, ModeLookup lookupOverride,
                          com.hydrokarbs.engine.port.PlatformPort platform, MatchMode... modes) {
        this.worlds = worlds;
        for (MatchMode mode : modes) {
            registry.register(fixedProvider(mode));
        }
        // 自理临时模板根 + 注册式地图 m1/m2(★文件式装载通道已删,造数走 MapTestKit 注册 fixture)
        Path templatesRoot = MapTestKit.newTemplatesRoot();
        maps = new MapRegistry(templatesRoot);
        MapTestKit.registered(maps, templatesRoot, "m1", TAG);
        MapTestKit.registered(maps, templatesRoot, "m2", TAG);
        Logger logger = Logger.getLogger("switch-harness");
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logLines.add(record.getLevel() + "|" + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        service = new SessionServiceImpl.Builder()
                .modes(lookupOverride != null ? lookupOverride : registry)
                .worlds(worlds)
                .maps(maps)
                .configSource(id -> OfflineAssembly.cfg())
                .policy(SessionPolicy.defaults(SessionPolicy.Topology.ROTATION))
                .fallbackModeId(fallbackModeId)
                .engineServices(new EngineServices(platform))
                .mainExecutor(Runnable::run)
                .hubRelocator(relocations::add)
                .safeStateCleaner(cleanups::add)
                .logger(logger)
                .build();
        ticker = new EngineTicker(null, service);   // Plugin 位置传 null:不 start,直调 tickAll
    }

    /** 建会话并推进到首切实例就位(两拍:发起 + 执行)。 */
    Session createSession(String id, String initialModeId) {
        return createSession(id, initialModeId, java.util.List.of());
    }

    /** 带会话组件表的建会话(2.6:装配序 = 传入序 = 派发序)。 */
    Session createSession(String id, String initialModeId,
                          List<com.hydrokarbs.engine.component.SessionComponent.Specification<?>> sessionComponents) {
        Session session = service.create(
                new SessionSpec(new SessionId(id), initialModeId, List.of(), sessionComponents));
        ticker.tickAll();
        ticker.tickAll();
        assertTrue(session.state() == SessionState.RUNNING || session.state() == SessionState.ENDING,
                "会话建后应回常态(初始模式可装配时),实际 " + session.state());
        return session;
    }

    /** 便捷:无参版装配的服务面(节拍器驱动断言复用)。 */
    static SessionService asService(SessionServiceImpl impl) {
        return impl;
    }

    /** 订阅会话全部事件到列表(事件序列断言用)。 */
    static List<SessionEvent> recordEvents(Session session) {
        List<SessionEvent> received = new ArrayList<>();
        session.events().subscribe(SessionEvent.class, received::add);
        return received;
    }

    /** 事件流的类型名序列(断言辅助)。 */
    static List<String> typeNames(List<SessionEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).collect(Collectors.toList());
    }

    /** 固定模式假 provider(注册表双发现的桩:build 返回预造模式)。 */
    static MatchModeProvider fixedProvider(MatchMode mode) {
        return new MatchModeProvider() {
            @Override
            public String id() {
                return mode.id();
            }

            @Override
            public MatchMode build(com.hydrokarbs.engine.session.MatchModeConfig cfg) {
                return mode;
            }
        };
    }

    // ── 模式便捷构造 ─────────────────────────────────────────────

    /** 空组件表模式(缺省去向=回退)。 */
    static MatchMode mode(String id, int min, int max) {
        return mode(id, min, max, new LinkedHashMap<>());
    }

    /** 带组件表的模式。 */
    static MatchMode mode(String id, int min, int max,
                          LinkedHashMap<String, com.hydrokarbs.engine.component.MatchModeComponent.Specification<?>> specs) {
        return mode(id, min, max, specs, MatchModeMeta.DefaultTransition.FALLBACK);
    }

    /** 带组件表与缺省去向的模式。 */
    static MatchMode mode(String id, int min, int max,
                          LinkedHashMap<String, com.hydrokarbs.engine.component.MatchModeComponent.Specification<?>> specs,
                          MatchModeMeta.DefaultTransition transition) {
        MatchMode.Builder builder = new MatchMode.Builder(id)
                .mapTags(TAG)
                .meta(new MatchModeMeta(false, min, max, transition));
        specs.forEach(builder::addComponent);
        return builder.build();
    }

    /** 名册 uuid 集(断言辅助)。 */
    static Set<UUID> rosterUuids(Session session) {
        return session.roster().all().stream()
                .map(com.hydrokarbs.engine.session.SessionMember::uuid)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
