package com.example.their;

import java.util.Set;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;

import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.session.MatchModeConfig;

/**
 * 第三方模式工厂:只有 Builder 声明,零生命周期代码(§13.4 示例的落地版;
 * 7.2 施工文档步骤 6)。模式即产品:每行一个组件声明,阶段/规则/结束判定全是
 * 构造数据——本示例不挂阶段与结束判定组件,永不自动结束,等外部 switch
 * ({@code DefaultTransition.STAY},§13.3 minilobby 同思路)。
 *
 * <p>声明要点(7.2 施工文档步骤 6):
 * <ul>
 *   <li>meta:空能力集(零外部依赖)+ {@code recordsHistory=false}(不落库,§10.1 注;
 *       ★v1.3:持久化已出框架基线,该声明保留为「模式自报不记史」语义)+ 人数窗 1~16;</li>
 *   <li>mapTags 用中性 tag {@code demo}——不依赖主仓任何玩法地图
 *       (地图解析按 tags 交集,§12.2;服主自备带 {@code demo} tag 的地图即适配);</li>
 *   <li>参数:配置随框架不随插件(§10.2)——需要参数时由服主把 yml 放进框架数据目录
 *       {@code modes/their_mode.yml};文件缺失按空配置构建(§15.2 铁律 1),本示例不强制带。</li>
 * </ul>
 */
public final class TheirModeFactory implements MatchModeProvider {

    @Override
    public String id() {
        return "their_mode";
    }

    @Override
    public int requiredApiVersion() {
        return 3;                       // ★v1.1-D7 声明:本工厂按 v3 面(地图注册)声明要求
    }

    @Override
    public MatchMode build(MatchModeConfig cfg) {
        return new MatchMode.Builder(id())
                .displayName(Component.text("Their Mode"))
                .icon(Material.BELL)
                .mapTags(Set.of("demo"))
                .meta(new MatchModeMeta(Set.of(), false, 1, 16,
                        MatchModeMeta.DefaultTransition.STAY))
                .addComponent("banner", new TheirBannerComponent.Spec())
                .build();
    }
}
