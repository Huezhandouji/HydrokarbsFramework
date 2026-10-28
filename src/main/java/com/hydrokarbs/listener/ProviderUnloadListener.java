package com.hydrokarbs.listener;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import com.hydrokarbs.api.MiniGameApiImpl;

/**
 * 插件卸载兜底监听({@code PluginDisableEvent};14-陷阱清单 14.38 的框架侧
 * 兜底;7.5 施工决策 5/14):任何插件 disable 时按提供方摘除其经 api 面注册的
 * 全部注册物——消费方无需自建善后(13-扩展步骤 §13.10 回退口径),但句柄
 * (订阅/注册物)仍应在其 onDisable 显式反注册(防御性,14.38)。
 *
 * <p><b>三个职责块(同一监听器落位,注释分开——7.5 决策 5/14 的「可同一监听器
 * 或相邻落位」口径;★v1.7 起 D4 能力标签块随标签机制退场移除)</b>:
 * <ol>
 *   <li><b>★v1.6 显式注册模式</b>:{@code MiniGameApi#registerMode} 的按提供方摘除,
 *       逐 id 走与显式 unregisterMode 同一回退编排(fallback 切换或
 *       {@code FALLBACK_DISSOLVE},§13.4/§3.2);</li>
 *   <li><b>★v1.2 注册式地图</b>:{@code MiniGameApi#registerMap} 的按提供方摘除,
 *       逐张走与显式 unregisterMap 同一守卫——正用拒绝 = 保留并 WARN(摘除被拒
 *       是特性,20-D4),不因插件下线强拆存活对局;</li>
 *   <li><b>D14 会话组件贡献</b>:后续会话不再合并(已装配会话不受影响——无运行期
 *       拆卸,随 dissolve 自然消亡,D14)。</li>
 * </ol>
 *
 * <p><b>历史口径注(★v1.6)</b>:原 ServicesManager 事件半边
 * ({@code ServiceRegisterEvent}/{@code ServiceUnregisterEvent} 收录/摘除,
 * {@code ProviderServiceListener} 承载)随显式注册裁决退场——注册物现在只经
 * api 面进出,本类是插件生命周期唯一的兜底(14.38:Bukkit 会在 disable 时注销其
 * 服务,但兜底不依赖该保证)。回调只做目录摘除与回退发起;摘除日志由
 * {@code MiniGameApiImpl} 各摘除口自带。主线程派发(Bukkit 事件),无异步包装。
 */
public final class ProviderUnloadListener implements Listener {

    private final MiniGameApiImpl api;

    public ProviderUnloadListener(MiniGameApiImpl api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    /** 插件禁用:按提供方摘除其注册物(三职责块幂等,重复派发无害)。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin plugin = event.getPlugin();
        api.unregisterModesOf(plugin);              // ① ★v1.6 显式注册模式(回退编排同 unregisterMode)
        api.unregisterMapsOf(plugin);               // ② ★v1.2 注册式地图(20-D4)
        api.unregisterSessionComponentsOf(plugin);  // ③ D14 贡献(后续会话不再合并)
    }
}
