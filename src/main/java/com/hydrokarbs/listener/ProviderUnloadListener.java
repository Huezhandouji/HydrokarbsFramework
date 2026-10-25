package com.hydrokarbs.listener;

import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import com.hydrokarbs.api.MiniGameApiImpl;
import com.hydrokarbs.engine.component.SessionComponentContributionRegistry;

/**
 * 插件卸载兜底监听({@code PluginDisableEvent};14-陷阱清单 14.38/D8 的框架侧
 * 兜底;7.5 施工决策 5/14):任何插件 disable 时按提供方摘除其经 api 面注册的
 * 全部注册物——消费方无需自建善后(13-扩展步骤 §13.10 回退口径),但句柄
 * (订阅/能力标签)仍应在其 onDisable 显式回收(防御性,14.38)。
 *
 * <p><b>三个职责块(同一监听器落位,注释分开——7.5 决策 5/14 的「可同一监听器
 * 或相邻落位」口径)</b>:
 * <ol>
 *   <li><b>D4 能力标签</b>:{@code MiniGameApi#registerCapability} 的提供方归属
 *       映射摘除(引擎 {@code CapabilityRegistry} 仍 tag-only 形态,7.5 决策 5);</li>
 *   <li><b>★v1.2 注册式地图</b>:{@code MiniGameApi#registerMap} 的按提供方摘除,
 *       逐张走与显式 unregisterMap 同一守卫——正用拒绝 = 保留并 WARN(摘除被拒
 *       是特性,20-D4),不因插件下线强拆存活对局;</li>
 *   <li><b>D14 会话组件贡献</b>:后续会话不再合并(已装配会话不受影响——无运行期
 *       拆卸,随 dissolve 自然消亡,D14)。</li>
 * </ol>
 *
 * <p><b>与 {@link ProviderServiceListener} 的分工</b>:那边是 ServicesManager 事件
 * 半边(ServiceRegisterEvent/ServiceUnregisterEvent,事件即权威,模式摘除含会话
 * 回退编排);本类是插件生命周期的最后兜底(14.38:Bukkit 会在 disable 时注销其
 * 服务,但兜底不依赖该保证;贡献摘除两半边都到,幂等吸收)。回调只做目录摘除,
 * 不做会话编排;主线程派发(Bukkit 事件),无异步包装。
 */
public final class ProviderUnloadListener implements Listener {

    private final MiniGameApiImpl api;
    private final SessionComponentContributionRegistry contributions;
    private final Logger logger;

    public ProviderUnloadListener(MiniGameApiImpl api,
                                  SessionComponentContributionRegistry contributions,
                                  Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.contributions = Objects.requireNonNull(contributions, "contributions");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** 插件禁用:按提供方摘除其注册物(三职责块幂等,重复派发无害)。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin plugin = event.getPlugin();
        api.unregisterCapabilitiesOf(plugin);       // ① D4 能力标签(14.38/D8)
        api.unregisterMapsOf(plugin);               // ② ★v1.2 注册式地图(20-D4)
        if (contributions.unregister(plugin)) {     // ③ D14 贡献(后续会话不再合并)
            logger.info("会话组件提供方贡献已按插件兜底摘除(D14/14.38): " + plugin.getName());
        }
    }
}
