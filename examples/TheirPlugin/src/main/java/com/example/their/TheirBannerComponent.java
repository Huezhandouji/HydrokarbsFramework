package com.example.their;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.instance.Participant;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;

/**
 * 第三方私有组件:模式包内自建组件的「基类纪律照抄到位」最小样例
 * (07-公共API §7.2「第三方组件」行;7.2 施工文档步骤 6;照 02-核心概念 §2.4)。
 *
 * <p>纪律要点:
 * <ul>
 *   <li>{@code create} 是唯一装配点(构造私有,容器内单一创建路径,§2.4 四条件之④);
 *       svc 构造期注入,先于一切钩子(四条件之①);</li>
 *   <li>玩家可见副作用最早发生在 {@link #start()}(§2.4 钩子注释)——awake 期
 *       只解析依赖,这里只播报一句,零其他副作用;</li>
 *   <li>异常只隔离自己的实例(§3.1 quarantine,§13.4 约束段)——框架兜底,
 *       组件自身无需再包 try/catch;</li>
 *   <li>播报用 {@code Component.text} 固定文案:不含任何玩家可控串,零 MiniMessage
 *       注入面(§14.10)。</li>
 * </ul>
 */
public final class TheirBannerComponent extends MatchModeComponent {

    /** 装配描述符:泛型实参声明本组件类型,工厂 {@code create} 是唯一创建点(§2.4)。 */
    public static final class Spec extends MatchModeComponent.Specification<TheirBannerComponent> {

        public Spec() {
            super("their_banner");
        }

        @Override
        public TheirBannerComponent create(String id, MatchModeInstanceServices svc) {
            return new TheirBannerComponent(id, svc);
        }
    }

    private TheirBannerComponent(String id, MatchModeInstanceServices svc) {
        super(id, svc);
    }

    /** 开局播报一句(玩家可见副作用最早在这里;零其他副作用,§2.4)。 */
    @Override
    public void start() {
        for (Participant participant : svc().self().participants()) {
            UUID uuid = participant.uuid();
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(Component.text("[TheirMode] 欢迎来到第三方模式示例!",
                        NamedTextColor.GOLD));
            }
        }
    }
}
