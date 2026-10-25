package com.hydrokarbs;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import com.hydrokarbs.api.MiniGameApi;
import com.hydrokarbs.api.MiniGameApiImpl;
import com.hydrokarbs.bridge.config.ConfigRepository;
import com.hydrokarbs.bridge.world.BukkitWorldLoader;
import com.hydrokarbs.bridge.world.WorldPortImpl;
import com.hydrokarbs.command.MatchCommand;
import com.hydrokarbs.engine.EngineTicker;
import com.hydrokarbs.engine.component.SessionComponentContributionRegistry;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.port.ConfigView;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.PlatformPort;
import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;
import com.hydrokarbs.engine.session.CapabilityRegistry;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionServiceImpl;
import com.hydrokarbs.listener.ProviderServiceListener;
import com.hydrokarbs.listener.ProviderUnloadListener;
import com.hydrokarbs.listener.SessionIngestListener;
import com.hydrokarbs.service.GlobalBoard;
import com.hydrokarbs.service.GlobalLedger;
import com.hydrokarbs.util.Threads;

/**
 * L0 装配编排:单一静态装配类 + 单一编排函数(18 号修正案 D12;施工计划 1.6/2.3)。
 *
 * <p>{@link #assemble} 一函承载 onEnable 全部装配序列——线性装配语句,自上而下可读完;
 * {@link #shutdown} 镜像 onDisable。主类退化为两行委托。
 *
 * <p>D12 纪律:①静态类可有静态函数,<b>不许有静态可变状态</b>;②assemble 只含线性
 * 装配语句,超一屏抽私有分段 helper;③命名不叫 {@code Bootstrapper}。
 *
 * <p><b>装配内容</b>:配置装载与模式双发现(装载时序不变式:saveDefaults 先行 →
 * loadAll → discover)、能力标签、世界端口(模板复制/引用计数/副本处置,§3.7)、
 * 会话服务(两阶段切换/三级回退;平台触点全部收敛为注入函数)、进程级服务
 * (GlobalLedger/GlobalBoard)、全局节拍、会话入口监听、第三方模式收录/摘除监听
 * (ServicesManager 半边,7.2)与 Brigadier 命令、公共 API
 * 注册(ServicesManager,§7.2 表)。
 * <b>框架不创建任何会话</b>——会话唯一创建路径 = 宿主在自己的编排点调
 * {@code SessionService.create(SessionSpec)}(§3.2 冻结签名;拓扑 = 宿主调几次
 * create,§3.6 引擎零感知);「零会话起服」为合法状态({@link EngineTicker}
 * 对空会话集空转,{@link #shutdown} 对空集解散循环,均幂等)。
 *
 * <p>seal 失败处理:装配异常自 assemble 上抛 → onEnable 异常 → 服务端记录严重
 * 日志并禁用插件——不吞、不半启动。
 */
public final class HydrokarbsAssembly {

    private HydrokarbsAssembly() {
    }

    /**
     * 装配产物(不可变 record,由插件实例字段持有)。
     * {@code worldIo} 为世界端口 IO 执行器,onDisable 收口(防重载后线程残留);
     * {@code api} 为对外公共面实现(★19 冻结面;shutdown 先摘,防 reload 窗口悬挂引用)。
     */
    public record Context(ServiceRegistry services, EngineTicker ticker, SessionService sessions,
                          ExecutorService worldIo, MiniGameApi api) {
    }

    /** onEnable 全部装配序列(线性;失败上抛 = 插件拒载)。 */
    public static Context assemble(JavaPlugin plugin) {
        Threads.init(plugin);                                  // ① 平台回程口(既有)
        // 版本协商面输出(★v1.1-D7/14.36:消费方 onEnable 首行对照本值拒载不崩服)
        plugin.getLogger().info("Hydrokarbs API_VERSION=" + MiniGameApi.API_VERSION
                + "(07-公共API §7.2 版本协商;消费方低于要求请自拒载)");

        // ── 配置与模式注册(装载时序不变式:saveDefaults 先行 → loadAll → discover)──
        var dataDir = plugin.getDataFolder().toPath();
        var templatesRoot = dataDir.resolve("maps").resolve("templates");   // §15.2 约定(世界口与地图注册口同源)
        var configRepository = new ConfigRepository(dataDir,
                ConfigRepository.DefaultResourceSource.of(plugin), templatesRoot);
        try {
            configRepository.saveDefaults();                   // ② 首次释放默认资源(幂等)
            configRepository.loadAll();                        // ③ 全量装载(单文件失败跳过+SEVERE)
        } catch (IOException e) {
            throw new IllegalStateException("配置装载失败(数据目录不可写?): " + dataDir, e);
        }
        var modeRegistry = new MatchModeRegistry(configRepository::modeConfig);
        modeRegistry.discover();                               // ④ 模式双发现(0 个模式属正常)

        var capabilities = new CapabilityRegistry();           // ⑤ 能力标签(机制件;切换阶段 A2 消费)
        ConfigView cfg = configRepository;                     // ⑥ 惰性只读配置面

        // ── 世界端口(模板根 = 数据目录 maps/templates,§15.2;副本根 = 服务器世界容器;
        //    onEnable 零模板预复制,§14.2) ─────────────────────────────────
        var worldIo = Executors.newVirtualThreadPerTaskExecutor();   // 副本复制/删除 = 虚拟线程(§3.7)
        var worldPort = new WorldPortImpl(
                new BukkitWorldLoader(),
                templatesRoot,
                Bukkit.getWorldContainer().toPath(),
                Threads::runOnMain,                             // 世界装卸 = 主线程(§3.7)
                worldIo,
                Duration.ofSeconds(30),                         // 获取超时(§3.5 防护④)
                plugin.getLogger());

        // ── 会话服务(平台触点全部收敛为注入函数) ──────────────────────────
        var sessionEvents = new SessionEventBus();             // ⑨ 服务订阅面汇聚点(D3 镜像目的地)
        var sessionSvc = new SessionServiceImpl.Builder()
                .modes(modeRegistry)
                .capabilities(capabilities)
                .worlds(worldPort)
                .maps(configRepository.maps())
                .configSource(configRepository::modeConfig)
                .policy(sessionPolicy(cfg))
                // 空串 = 回退链跳级(直接二级解散兜底);框架不设出厂回退模式
                .fallbackModeId(cfg.get("session.fallback-mode", ""))
                .engineServices(new EngineServices(platformPort(plugin)))
                .mainExecutor(Threads::runOnMain)              // future 拒绝完成点回主线程
                .hubRelocator(hubRelocator())                  // 解散传送主城出生点(Bukkit 触点在此)
                .safeStateCleaner(safeStateCleaner())          // 三级兜底:清通用安全态
                .sessionEventMirror(sessionEvents::publish)    // ★v1.1-D3 镜像:会话事件同步汇聚到 ⑨(7.5 决策 12)
                .build();                                      // ⑧

        var contributions = new SessionComponentContributionRegistry();   // D14 收录表(进程级)
        var services = new ServiceRegistry(capabilities, plugin, cfg);
        services.install(ServiceSpec.of("global-ledger", GlobalLedger.class,
                        env -> new GlobalLedger(sessionEvents, sessionSvc, session -> Map.of()))
                .capabilities("global-ledger")
                .build());
        services.install(ServiceSpec.of("global-board", GlobalBoard.class,
                        env -> new GlobalBoard(sessionEvents))
                .capabilities("global-board")
                .build());
        services.seal();                                       // ⑩ 校验 + 拓扑 + 启动 + 标签

        var ticker = new EngineTicker(plugin, sessionSvc);     // ⑪ 全局节拍(周期读配置)
        ticker.start(cfg.get("engine.tick-period", 1));

        plugin.getServer().getPluginManager()
                .registerEvents(new SessionIngestListener(sessionSvc), plugin);   // ⑫ 会话入口监听
        // ⑫b 第三方收录/摘除监听(7.2 + 7.5-D14):构造期 loadAll 补收录先启第三方,
        //     ServiceRegisterEvent/ServiceUnregisterEvent 动态增删(§13.4;事件即权威,不轮询)
        plugin.getServer().getPluginManager().registerEvents(
                new ProviderServiceListener(modeRegistry, sessionSvc, cfg, contributions,
                        plugin.getLogger()), plugin);
        MatchCommand.register(plugin, sessionSvc, modeRegistry);                  // ⑬ Brigadier 主命令

        // ⑭ 公共 API 注册(ServicesManager 同一机制,ServicePriority.Normal——§7.2 表;
        //    薄委托实现,主线程回程口 = Threads::runOnMain;v3 冻结面 = 16 方法)
        var miniGameApi = new MiniGameApiImpl(sessionSvc, modeRegistry,
                configRepository.maps(), capabilities, sessionEvents, contributions,
                Threads::runOnMain);
        plugin.getServer().getServicesManager()
                .register(MiniGameApi.class, miniGameApi, plugin, ServicePriority.Normal);
        // ⑭b 插件卸载兜底监听(14.38/D8/★v1.2):按提供方摘能力标签/注册式地图/贡献
        plugin.getServer().getPluginManager().registerEvents(
                new ProviderUnloadListener(miniGameApi, contributions, plugin.getLogger()), plugin);

        return new Context(services, ticker, sessionSvc, worldIo, miniGameApi);
    }

    /** onDisable 镜像:先摘公共面 → 停节拍 → 解散全部会话(传主城,含世界句柄释放) → 逆序关停服务 → 收执行器。 */
    public static void shutdown(Context ctx) {
        // 公共面先摘(幂等):显式注销保证 reload 期间第三方 load() 不拿悬挂实现
        // (7.1 要点 8;14.22 晚注册/早注销都要有路径的对称纪律)
        Bukkit.getServicesManager().unregister(MiniGameApi.class, ctx.api());
        ctx.ticker().close();           // ★ 必取消,否则重载后任务翻倍(14.6)
        for (var session : ctx.sessions().all()) {
            ctx.sessions().dissolve(session.id(), DissolveReason.SHUTDOWN);   // §12.2 关停驱动
        }
        ctx.services().shutdown();      // 逆序 safeStop + 幂等 + 摘能力标签(★38)
        ctx.worldIo().shutdown();       // 世界 IO 执行器收口(已提交任务放行完成,不阻塞等待)
        Threads.shutdown();
    }

    // ── 配置块 → 引擎值(缺键回落默认,§15.2 铁律 1) ────────────────────

    /**
     * {@code session:} 块 → 会话策略(min-dwell 与断线宽限是内核节律,与谁建会话无关)。
     * {@code topology} 字段无框架行为消费(仅 create 日志回显),装配固定
     * {@link SessionPolicy.Topology#ROTATION} 占位——部署拓扑由宿主经 create 次数表达(§3.6)。
     */
    private static SessionPolicy sessionPolicy(ConfigView cfg) {
        return new SessionPolicy(
                SessionPolicy.Topology.ROTATION,
                cfg.get("session.min-dwell-ticks", SessionPolicy.DEFAULT_MIN_DWELL_TICKS),
                cfg.get("session.disconnect-grace-seconds", SessionPolicy.DEFAULT_DISCONNECT_GRACE_SECONDS));
    }

    // ── 平台函数注入(引擎不直碰 Bukkit;解散传送与兜底清态的落点) ──────────

    /** 平台端口:日志/插件句柄/一次性延迟任务(实例侧 later 的调度口)。 */
    private static PlatformPort platformPort(JavaPlugin plugin) {
        return new PlatformPort() {
            @Override
            public java.util.logging.Logger logger() {
                return plugin.getLogger();
            }

            @Override
            public Plugin plugin() {
                return plugin;
            }

            @Override
            public AutoCloseable scheduleOnce(long delayTicks, Runnable task) {
                var handle = Bukkit.getGlobalRegionScheduler()
                        .runDelayed(plugin, scheduled -> task.run(), Math.max(1L, delayTicks));
                return handle::cancel;
            }
        };
    }

    /** 解散成员传送:主城出生点 = 服务器主世界出生点(设计未细化,最小自洽)。 */
    private static Consumer<Collection<UUID>> hubRelocator() {
        return members -> {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                return;
            }
            Location spawn = worlds.get(0).getSpawnLocation();
            for (UUID uuid : members) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.teleport(spawn);
                }
            }
        };
    }

    /** 三级兜底清态:通用安全态(生存模式/取消隐身)——设计未细化,最小自洽。 */
    private static Consumer<Collection<UUID>> safeStateCleaner() {
        return members -> {
            for (UUID uuid : members) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.setGameMode(GameMode.SURVIVAL);
                    player.setInvisible(false);
                }
            }
        };
    }
}
