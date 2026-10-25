package com.hydrokarbs.testkit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.hydrokarbs.api.MiniGameApiImpl;
import com.hydrokarbs.engine.EngineTicker;
import com.hydrokarbs.engine.component.SessionComponentContributionRegistry;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.session.CapabilityRegistry;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionServiceImpl;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SessionState;
import com.hydrokarbs.map.MapDefinition;
import com.hydrokarbs.map.MapRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api 集成测试基座(7.5;test 源集):真会话服务(假世界口/直通主线程/镜像挂接)+
 * 真模式注册表 + 真地图目录(带模板根)+ 真能力注册表 + ⑨ 汇聚总线 + D14 贡献收录表
 * + 真 {@link MiniGameApiImpl}——★42/★43/★51~★53/★54 的离线端到端底座
 * (镜像 {@code SwitchHarness} 2.3 装配先例;离线零 Bukkit 静态调用)。
 *
 * <p>组装口径:主线程执行器 = 直跑(切换完成点内联,与 SwitchHarness 同款);
 * 镜像挂接 = {@code sessionEventMirror(globalEvents::publish)}(生产行为的离线复刻,
 * 7.5 决策 12);会话创建后直调节拍器两拍推进首切实例(§16.4 三律①)。
 */
public final class OfflineApi {

    /** 共享测试标签(模式与注册式地图都带它,标签过滤可预期)。 */
    public static final Set<String> TAG = Set.of("common");

    public final MatchModeRegistry modes;
    public final MapRegistry maps;
    public final CapabilityRegistry capabilities;
    public final SessionEventBus globalEvents;
    public final SessionComponentContributionRegistry contributions;
    public final SessionServiceImpl service;
    public final EngineTicker ticker;
    public final MiniGameApiImpl api;
    /** 服务日志捕获(WARN/SEVERE 断言面;镜像 SwitchHarness)。 */
    public final List<LogRecord> logs = new ArrayList<>();

    /**
     * @param templatesRoot  地图注册通道的模板根(可指向 @TempDir 下目录;null = 仅文件
     *                       装载通道——注册式不可用,注册即响亮抛,MapRegistry 同口径)
     * @param worlds         世界端口假件(即时成功/注入失败)
     * @param modeFactories  预造模式的假工厂(注册表以 {@code register} 启动期口径收录)
     */
    public OfflineApi(Path templatesRoot, FakeWorldPort worlds, MatchMode... modeFactories) {
        Logger logger = Logger.getLogger("offline-api-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        this.modes = new MatchModeRegistry(id -> OfflineAssembly.cfg(), logger);
        for (MatchMode mode : modeFactories) {
            modes.register(fixedProvider(mode));
        }
        this.maps = templatesRoot == null ? new MapRegistry() : new MapRegistry(templatesRoot);
        this.capabilities = new CapabilityRegistry();
        this.globalEvents = new SessionEventBus();
        this.contributions = new SessionComponentContributionRegistry();
        this.service = new SessionServiceImpl.Builder()
                .modes(modes)
                .capabilities(capabilities)
                .worlds(worlds)
                .maps(maps)
                .configSource(id -> OfflineAssembly.cfg())
                .policy(SessionPolicy.defaults(SessionPolicy.Topology.ARENA))
                .fallbackModeId("")                            // ★v1.3 R3:空 = 跳级直接解散
                .engineServices(new EngineServices(new OfflineAssembly.FakePlatform()))
                .mainExecutor(Runnable::run)                   // 直通:切换完成点内联
                .sessionEventMirror(globalEvents::publish)     // ★v1.1-D3 镜像(7.5 决策 12)
                .logger(logger)
                .build();
        this.ticker = new EngineTicker(null, service);         // Plugin 位传 null:不 start,直调驱动
        this.api = new MiniGameApiImpl(service, modes, maps, capabilities, globalEvents,
                contributions, Runnable::run);
    }

    /** 建会话并推进到首切实例就位(真服务;两拍驱动,镜像 SwitchHarness.createSession)。 */
    public Session createSession(String id, String initialModeId,
                                 List<SessionComponent.Specification<?>> sessionComponents) {
        Session session = service.create(
                new SessionSpec(new SessionId(id), initialModeId, List.of(), sessionComponents));
        ticker.tickAll();
        ticker.tickAll();
        assertTrue(session.state() == SessionState.RUNNING || session.state() == SessionState.ENDING,
                "会话建后应回常态(初始模式可装配时),实际 " + session.state());
        return session;
    }

    /** 无组件表便捷版。 */
    public Session createSession(String id, String initialModeId) {
        return createSession(id, initialModeId, List.of());
    }

    /** 服务日志是否含指定级别 + 片段(断言辅助;参数化行经 MessageFormat 展开)。 */
    public boolean hasLog(Level level, String fragment) {
        return logs.stream().anyMatch(record -> record.getLevel() == level
                && formatted(record).contains(fragment));
    }

    private static String formatted(LogRecord record) {
        Object[] params = record.getParameters();
        return params == null ? String.valueOf(record.getMessage())
                : java.text.MessageFormat.format(record.getMessage(), params);
    }

    /** 最小模式(标签可配;人数窗 0~100,空名册可切)。 */
    public static MatchMode mode(String id, Set<String> mapTags) {
        return new MatchMode.Builder(id)
                .mapTags(mapTags)
                .meta(new MatchModeMeta(Set.of(), false, 0, 100,
                        MatchModeMeta.DefaultTransition.STAY))
                .build();
    }

    /** 最小注册式地图(带出生点组,便于后续消费;模板目录须由调用方先落盘)。 */
    public static MapDefinition map(String id, Set<String> tags) {
        return new MapDefinition(id, "world_" + id, tags, Map.of());
    }

    /** 固定模式假 provider(注册表双发现的桩:build 返回预造模式)。 */
    private static MatchModeProvider fixedProvider(MatchMode mode) {
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
}
