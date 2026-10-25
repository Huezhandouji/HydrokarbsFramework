package com.example.their;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.api.SessionComponentProvider;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.port.SessionServices;

/**
 * 消费样例守卫用例(7.5 施工文档步骤 10 的测试源集载体;★46/★51 的消费方侧):
 * <ul>
 *   <li><b>低版本拒载用例(★46/14.36)</b>:{@code onEnable} 首行版本判定的真值表——
 *       api 缺席/版本低于要求 → 拒载,恰好满足 → 放行(拒载路径本体
 *       {@code setEnabled(false)} 需活服,由 runServer 冒烟覆盖,不在本子阶段);</li>
 *   <li><b>坏 provider 用例(★51 的消费方侧形状)</b>:声明一个贡献了「无人供给依赖」
 *       组件的 provider——它在框架侧被收录闸干跑校验响亮拒收(SEVERE、会话照常,
 *       行为本体由框架侧 SessionComponentContributionTest 承载,样例只示范形状);
 *       同时断言合法 provider 的 SPI 契约(requiredApiVersion/非空贡献表)。</li>
 * </ul>
 *
 * <p>零 Bukkit 静态调用(compileOnly paper-api 仅类型可见;★45:样例仅依赖白名单)。
 */
class TheirPluginGuardsTest {

    // ── ★46 低版本拒载:版本判定真值表 ─────────────────────────────

    @Test
    void lowOrMissingApiVersionDisablesPlugin() {
        assertTrue(TheirPlugin.shouldDisable(null, TheirPlugin.REQUIRED_API_VERSION),
                "api 缺席 → 拒载(14.36)");
        assertTrue(TheirPlugin.shouldDisable(2, TheirPlugin.REQUIRED_API_VERSION),
                "API_VERSION 低于要求 → 拒载(14.36)");
        assertFalse(TheirPlugin.shouldDisable(3, TheirPlugin.REQUIRED_API_VERSION),
                "恰好满足 → 放行(One-Version Rule,不要求更新面)");
        assertFalse(TheirPlugin.shouldDisable(4, TheirPlugin.REQUIRED_API_VERSION),
                "更高版本 → 放行(只增不减,D7)");
    }

    // ── ★51 消费方侧:合法 provider 契约 + 坏 provider 形状 ─────────────

    @Test
    void legitimateProviderDeclaresContributionsAndVersion() {
        SessionComponentProvider provider = new TheirPlugin.TheirComponentProvider();
        assertEquals(TheirPlugin.REQUIRED_API_VERSION, provider.requiredApiVersion(),
                "贡献提供方声明与插件一致的最低版本(D7)");
        assertFalse(provider.sessionComponents().isEmpty(),
                "贡献表非空(D14 通道一;空贡献表无收录意义)");
    }

    @Test
    void brokenProviderShapeIsExpressibleAndFrameworkOwesRejection() {
        // 坏 provider:贡献声明依赖一个无人供给的组件类型——框架收录闸干跑校验
        // (组件 id 查重/必需依赖)会响亮拒收录本 provider、会话照常(D14;★51)。
        SessionComponentProvider broken = () -> List.<SessionComponent.Specification<?>>of(
                new BrokenSpec().requires(UnresolvedSessionComponent.class));

        assertEquals(1, broken.sessionComponents().size(), "坏 provider 形状可表达");
        assertEquals(List.of(UnresolvedSessionComponent.class),
                broken.sessionComponents().get(0).freeze().requiredTypes(),
                "坏声明 = 带无人供给的必需依赖(框架侧干跑校验的拒绝输入)");
        // 拒绝行为本体:框架侧 SessionComponentContributionTest#badProviderRejectedSeverely...
    }

    /** 坏贡献描述符(泛型实参可解析,freeze 口径与真实声明一致)。 */
    private static final class BrokenSpec
            extends SessionComponent.Specification<UnresolvedSessionComponent> {

        BrokenSpec() {
            super("their-broken-contribution");
        }

        @Override
        public UnresolvedSessionComponent create(String id, SessionServices svc) {
            throw new UnsupportedOperationException("坏贡献不会被装配(拒收录在先)");
        }
    }

    /** 无人供给的会话组件类型(坏声明的依赖目标)。 */
    private abstract static class UnresolvedSessionComponent extends SessionComponent {

        UnresolvedSessionComponent(String id, SessionServices svc) {
            super(id, svc);
        }
    }
}
