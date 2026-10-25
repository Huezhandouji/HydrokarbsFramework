package com.hydrokarbs.listener;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import com.hydrokarbs.api.SessionComponentProvider;
import com.hydrokarbs.engine.component.SessionComponentContributionRegistry;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.port.ConfigView;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SwitchResult;

/**
 * 第三方服务收录/摘除监听:ServicesManager 半边的运行期承载
 * (07-公共API §7.2「第三方注册模式」行;13-扩展步骤 §13.4;7.2 施工文档步骤 2;
 * ★v1.1 扩 D14 会话组件贡献收录,7.5 施工文档步骤 6)。
 *
 * <p><b>两个职责块(事件过滤各自独立;同一监听器落位,7.5 施工决策 5 的「二选一」)</b>:
 * <ul>
 *   <li><b>模式工厂</b>(7.2):第三方 {@code register(MatchModeProvider.class, ...)}
 *       → {@code ServiceRegisterEvent} 收录({@code build()} + verifyDependencies,
 *       坏模板跳过并 SEVERE、不进注册表)→ {@code ServiceUnregisterEvent} 摘除并让
 *       受影响会话回退——<b>别的插件不改我们的 jar 就能加模式</b>(§7.2 表);</li>
 *   <li><b>会话组件贡献</b>(D14 通道一):第三方
 *       {@code register(SessionComponentProvider.class, ...)} → 收录时<b>干跑合并校验</b>
 *       (组件 id 查重/必需依赖,在贡献收录表内完成),坏 provider SEVERE 拒收录、
 *       会话照常;收录后所有 {@code createSession} 合并其贡献;摘除后半边 +
 *       {@code PluginDisableEvent} 兜底(见 {@link ProviderUnloadListener})
 *       = 后续会话不再合并(14.38/D14)。</li>
 * </ul>
 *
 * <p><b>先启场景补收录</b>:第三方先于本框架启用时,它注册服务时尚无人监听——
 * 构造期 {@code getRegistrations(...)} 一次补齐两类提供方
 * (§13.4 启动顺序表;接口无 {@code loadAll},见构造器 API 事实注);此后动态增删
 * 全走事件。事件即权威,不用轮询兜底
 * (§15.1 {@code CapabilityListener} 同款先例——轮询会掩盖「摘除未触发回退」的真缺陷)。
 *
 * <p><b>摘除回退(模式半边;§13.4;镜像 §3.2 三级回退二级语义)</b>:对每个
 * <b>当前模式 id ∈ 被摘集</b>的会话——
 * <ul>
 *   <li>{@code session.fallback-mode}(经 {@link ConfigView} 惰性读,不写死任何 id,
 *       §14.21)已配置且已注册 → 发起 {@code switchMode}(force=true,豁免
 *       min-dwell;入口①外部调用,§3.2/§3.5;{@link Handoff#empty()});</li>
 *   <li>fallback 空或未注册 → 直接解散({@code FALLBACK_DISSOLVE},§3.2 二级;
 *       ★v1.3 R3 空值语义同款)。</li>
 * </ul>
 * 返回 {@code Rejected(SWITCH_IN_PROGRESS)} → WARN 放弃,<b>不在事件栈自旋重试</b>
 * (§14.16/§14.17;后续由既有回退/维护路径兜底,§3.1)。
 *
 * <p><b>时序(§16.4 会话三律①)</b>:事件回调里只<b>发起</b>切换/解散;
 * 真正执行由 EngineTicker 下一 tick 驱动——回调内不做任何异步包装
 * (ServicesManager 事件主线程派发,SessionService 读写主线程,§10.5)。
 *
 * <p><b>事件过滤要严(7.2 要点 2)</b>:ServicesManager 上还有
 * {@code MiniGameApi.class}(本框架自己)等注册——不按
 * {@code getProvider().getService() == 类型} 过滤会把它们当提供方收录
 * (方法名按 paper-api 1.21.11 实测:javap 核实为 {@code getService},
 * 施工文档伪代码写作 getType)。
 *
 * <p><b>可测性缝</b>:{@code ingestProvider}/{@code removeProvider}/
 * {@code ingestContributions}/{@code removeContributions} 是收录/摘除的公开内核,
 * Bukkit 事件方法只是一层类型过滤薄壳——事件对象需活服才能构造,离线测试直调内核;
 * 事件全链联调由 runServer 冒烟覆盖(冒烟归后续独立阶段,7.2 同款口径)。
 */
public final class ProviderServiceListener implements Listener {

    /** 回退目标配置键(§14.21:fallback 是配置项,引擎/监听按 id 查表、不认识任何具体值)。 */
    private static final String FALLBACK_MODE_KEY = "session.fallback-mode";

    /**
     * 会话组件提供方的键控收录条目(先启补收录源载体;D14 通道一)。
     *
     * <p>提供方身份键与摘除半边<b>同源</b>:生产 = 注册插件
     * ({@code RegisteredServiceProvider#getPlugin}——ServiceUnregisterEvent 与
     * PluginDisableEvent 兜底都按它摘除,14.38/D14);离线测试注入任意身份对象
     * (收录表键形态 {@code Object} 的零 Bukkit 纪律,见
     * {@link SessionComponentContributionRegistry} 类注)。
     */
    public record KeyedComponentProvider(Object providerKey, SessionComponentProvider provider) {
    }

    private final MatchModeRegistry registry;
    private final SessionService sessions;
    private final ConfigView config;
    private final SessionComponentContributionRegistry contributions;
    private final Logger logger;

    /**
     * 生产构造:构造期做初始收录——把先启第三方的模式工厂与会话组件提供方补进
     * 各自注册面(§13.4 启动顺序表「TheirPlugin 先启」行)。
     *
     * <p>API 事实注:施工文档骨架写 {@code loadAll(Class)},paper-api 1.21.11 的
     * {@code ServicesManager} 接口无该方法(javap 核实)——等价面
     * {@code getRegistrations(Class)} 展开 provider 列表,行为一致(跨插件全量收录)。
     * 贡献半边的键取 {@code getPlugin()}(注册插件),与摘除半边同键(14.38)。
     */
    public ProviderServiceListener(MatchModeRegistry registry, SessionService sessions,
                                   ConfigView config,
                                   SessionComponentContributionRegistry contributions,
                                   Logger logger) {
        this(registry, sessions, config, contributions, logger,
                () -> Bukkit.getServicesManager().getRegistrations(MatchModeProvider.class).stream()
                        .map(RegisteredServiceProvider::getProvider)
                        .toList(),
                () -> Bukkit.getServicesManager()
                        .getRegistrations(SessionComponentProvider.class).stream()
                        .map(rsp -> new KeyedComponentProvider(rsp.getPlugin(), rsp.getProvider()))
                        .toList());
    }

    /**
     * 离线测试构造口:两类初始收录源注入(生产行为与上例公开构造一致;镜像
     * {@code MatchModeRegistry} 日志注入构造口先例——离线测试零 Bukkit 静态调用)。
     * 贡献半边经 {@link KeyedComponentProvider} 注入身份键,键语义与生产同构。
     */
    public ProviderServiceListener(MatchModeRegistry registry, SessionService sessions,
                                   ConfigView config,
                                   SessionComponentContributionRegistry contributions,
                                   Logger logger,
                                   Supplier<List<MatchModeProvider>> existingModeProviders,
                                   Supplier<List<KeyedComponentProvider>> existingComponentProviders) {
        this.registry = registry;
        this.sessions = sessions;
        this.config = config;
        this.contributions = contributions;
        this.logger = logger;
        for (MatchModeProvider provider : existingModeProviders.get()) {
            ingestProvider(provider);                  // 先启场景补收录(同一注册闸)
        }
        for (KeyedComponentProvider keyed : existingComponentProviders.get()) {
            ingestContributions(keyed.providerKey(), keyed.provider());   // 键 = 注册插件(与摘除半边同键,D14/14.38)
        }
    }

    /** 服务注册事件:类型过滤后分流收录(NORMAL;§15.1 优先级表先例)。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == MatchModeProvider.class) {
            ingestProvider((MatchModeProvider) event.getProvider().getProvider());
            return;
        }
        if (event.getProvider().getService() == SessionComponentProvider.class) {
            // 提供方键 = 注册插件(D14/14.38:插件 disable 兜底按插件摘除,
            // RegisteredServiceProvider#getPlugin 是唯一权威来源)
            ingestContributions(event.getProvider().getPlugin(),
                    (SessionComponentProvider) event.getProvider().getProvider());
        }
    }

    /** 服务摘除事件:类型过滤后分流摘除(模式半边做受影响会话回退/解散)。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == MatchModeProvider.class) {
            removeProvider((MatchModeProvider) event.getProvider().getProvider());
            return;
        }
        if (event.getProvider().getService() == SessionComponentProvider.class) {
            removeContributions(event.getProvider().getPlugin());
        }
    }

    // ── 内核一:模式工厂收录/摘除(7.2 既有;requiredApiVersion 注册处打点) ──

    /**
     * 收录内核:委托 {@link MatchModeRegistry#registerProvider}(运行期口径:
     * 坏模板 SEVERE + 跳过 + 不上抛)。事件与初始收录两路汇合同一闸(§14.12:不按
     * 提供方来源分支)。收录点打版本协商行(D7/14.36:对每个 Provider 记录其
     * 声明要求;框架 onEnable 的 API_VERSION 输出在装配点)。
     */
    public void ingestProvider(MatchModeProvider provider) {
        logger.log(Level.INFO, "模式工厂收录: id={0}, requiredApiVersion={1}(D7 版本协商打点)",
                new Object[] { provider.id(), provider.requiredApiVersion() });
        registry.registerProvider(provider);
    }

    /**
     * 摘除内核:目录摘除({@link MatchModeRegistry#unregisterProvider})+
     * 对每个当前模式 id ∈ 被摘集的会话发起回退/解散(编排归 listener 层,
     * 注册表不做会话编排,§3.1)。返回被摘 id 集(空集 = 该提供方无注册)。
     */
    public Set<String> removeProvider(MatchModeProvider provider) {
        Set<String> removed = registry.unregisterProvider(provider);
        if (removed.isEmpty()) {
            return removed;
        }
        String fallback = config.get(FALLBACK_MODE_KEY, "").strip();
        boolean fallbackUsable = !fallback.isEmpty() && isRegistered(fallback);
        int redirected = 0;
        int dissolved = 0;
        for (Session session : sessions.all()) {
            String current = session.currentInstance().map(MatchModeInstance::modeId).orElse("");
            if (!removed.contains(current)) {
                continue;                                  // 切换间隙无实例(empty)→ 不匹配、不重复编排
            }
            if (fallbackUsable) {
                initiateFallback(session, fallback);
                redirected++;
            } else {
                sessions.dissolve(session.id(), DissolveReason.FALLBACK_DISSOLVE);
                dissolved++;
            }
        }
        logger.log(Level.INFO, "第三方模式摘除善后:回退 {0} 个会话,解散 {1} 个会话(被摘 id: {2})",
                new Object[] { redirected, dissolved, removed });
        return removed;
    }

    /**
     * 发起回退切换(入口①外部调用,force 豁免 min-dwell,§3.5):返回的 future
     * 只挂 WARN 观测——拒绝(典型 {@code SWITCH_IN_PROGRESS})放弃不自旋(§14.17),
     * 执行由节拍器下一 tick 驱动(§16.4 三律①)。
     */
    private void initiateFallback(Session session, String fallback) {
        MatchModeSelection selection = new MatchModeSelection(fallback, Optional.empty(), true);
        sessions.switchMode(session.id(), selection, Handoff.empty()).whenComplete((result, err) -> {
            if (err != null) {
                logger.log(Level.WARNING, "会话 " + session.id().value() + " 回退切换异常: " + err);
                return;
            }
            if (result instanceof SwitchResult.Rejected rejected) {
                logger.log(Level.WARNING, "会话 " + session.id().value() + " 回退到 " + fallback
                        + " 被拒[" + rejected.code() + "]: " + rejected.detail()
                        + "(放弃,不重试)");
            }
        });
    }

    /** 模式 id 是否已注册(摘除后判定 fallback 可用性;走 all() 快照,不新增注册表面)。 */
    private boolean isRegistered(String modeId) {
        return registry.all().stream().anyMatch(mode -> mode.id().equals(modeId));
    }

    // ── 内核二:会话组件贡献收录/摘除(D14 通道一;7.5 施工文档步骤 6) ──────

    /**
     * 贡献收录内核:干跑合并校验(组件 id 查重/必需依赖)在贡献收录表内完成——
     * 校验失败 → SEVERE 拒收录、会话照常(贡献不进默认面,坏 provider 的异常
     * 不上抛:事件回调抛异常会中断整轮事件派发,7.2 要点 3 同款运行期口径);
     * 通过 → 落地,此后所有 {@code createSession} 合并其贡献(D14)。
     *
     * @param providerKey 提供方身份键(生产 = 注册插件 {@code RegisteredServiceProvider#getPlugin};
     *                    插件 disable 兜底按它摘除,14.38/D14)
     * @param provider    会话组件提供方
     */
    public void ingestContributions(Object providerKey, SessionComponentProvider provider) {
        try {
            contributions.register(providerKey, provider.sessionComponents());
        } catch (RuntimeException bad) {
            logger.log(Level.SEVERE, "会话组件提供方拒收录(干跑合并校验失败;贡献不进默认面,"
                            + "会话照常,D14): key={0}, provider={1}, 原因: {2}",
                    new Object[] { providerKey, provider.getClass().getName(),
                            String.valueOf(bad.getMessage()) });
            return;
        }
        logger.log(Level.INFO, "会话组件提供方已收录: key={0}, provider={1}, requiredApiVersion={2}, "
                        + "贡献 {3} 项(此后所有新会话合并其贡献,D14)",
                new Object[] { providerKey, provider.getClass().getName(),
                        provider.requiredApiVersion(), provider.sessionComponents().size() });
    }

    /**
     * 贡献摘除内核:后续会话不再合并(已装配会话不受影响——无运行期拆卸,
     * 随 dissolve 自然消亡,D14)。幂等(未收录静默)。
     *
     * @param providerKey 提供方身份键(生产 = 注册插件;与收录同键)
     */
    public void removeContributions(Object providerKey) {
        if (contributions.unregister(providerKey)) {
            logger.log(Level.INFO, "会话组件提供方贡献已摘除(后续会话不再合并;已装配会话不受影响,D14): {0}",
                    providerKey);
        }
    }
}
