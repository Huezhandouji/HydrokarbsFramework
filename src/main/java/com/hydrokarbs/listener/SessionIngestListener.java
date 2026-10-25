package com.hydrokarbs.listener;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionService;

/**
 * 会话入口监听:进服/退服/被踢/重生四事件路由到会话服务
 * (15-PaperMC 适配 §15.1 优先级表 SessionIngestListener 行:NORMAL、ignoreCancelled=false)。
 *
 * <p><b>只路由不做重活</b>(§15.1 规范表:回调内「路由到会话 → 发内部事件 → 返回」):
 * <ul>
 *   <li>进服:已在某会话名册的断线成员 → 宽限销账 + 置回在线 + 发重连事件;
 *       未入会话的新玩家 → 恰有唯一存活会话(rotation 单会话形态)时自动收录,
 *       多会话(arena)不自动路由——入口牌子/NPC 等定向入口归外部插件(设计未细化,
 *       最小自洽:单会话收录、多会话忽略并记日志);</li>
 *   <li>退服:断线宽限标记(成员保留在册,宽限语义见会话服务);</li>
 *   <li>被踢:立即移除(宽限仅服务连接意外中断);</li>
 *   <li>重生:定位所属会话与当前实例——重生安置是组件层职责(阶段4 重生组件),
 *       本阶段路由到「查无则已」即返回,不抢占组件层的安置点。</li>
 * </ul>
 */
public final class SessionIngestListener implements Listener {

    private final SessionService sessions;

    public SessionIngestListener(SessionService sessions) {
        this.sessions = sessions;
    }

    /** 进服:宽限期内重连恢复;否则(唯一会话形态)自动收录。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        Optional<Session> owning = sessions.sessionOf(player);
        if (owning.isPresent()) {
            Session session = owning.get();
            session.tracker().onReconnect(player);        // 宽限销账(此后清点不再点名)
            session.roster().markReconnected(player);     // 置回在线(未断线为 no-op)
            session.events().publish(SessionEvent.MemberReconnected.of(session.id(), player));
            return;
        }
        var alive = sessions.all();
        if (alive.size() == 1) {                          // rotation 单会话:全员进它(§3.6)
            Session the = alive.iterator().next();
            sessions.join(the.id(), player);
        }
        // 多会话(arena):新玩家的定向入口归外部插件,不自动路由
    }

    /** 退服:断线视同离开(宽限标记 + 即发离开事件;宽限期内重连即恢复)。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.leave(event.getPlayer().getUniqueId(), com.hydrokarbs.engine.session.LeaveReason.QUIT);
    }

    /** 被踢:立即移除(不享受宽限)。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerKick(PlayerKickEvent event) {
        sessions.leave(event.getPlayer().getUniqueId(), com.hydrokarbs.engine.session.LeaveReason.KICK);
    }

    /** 重生:路由到所属会话的当前实例(安置点归组件层,本阶段只定位不动作)。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Optional<Session> owning = sessions.sessionOf(event.getPlayer().getUniqueId());
        if (owning.isEmpty()) {
            return;
        }
        // 定位到所属会话的当前实例即返回——重生安置(出生点选择/观战位等)
        // 是实例组件层(阶段4 重生组件)的接手点,会话入口不抢占。
        owning.get().currentInstance();
    }
}
