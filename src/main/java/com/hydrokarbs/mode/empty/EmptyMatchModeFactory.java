package com.hydrokarbs.mode.empty;

import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.session.MatchModeConfig;

/**
 * 出厂兜底占位模式(31 号修正案):唯一职责 = <b>回退落点</b>——保证会话任何时刻
 * 有模式实例可泊(一级回退/首切失败/摘除回退共用同一落点)。零组件、零配置面:
 * 不读 cfg(mapTags 缺省空集 = 通配任意地图,31 号决策 B,仍受会话地图池过滤)、
 * recordsHistory=false(占位等待不落史)、人数窗 (0, {@link Integer#MAX_VALUE})
 * ——force 只豁免人数下限、上限永不豁免(A4/防护③),上限全开才能兜任意名册;
 * displayName/description 走 Builder 缺省兜底、icon 保持 null(既有口径,消费方自理)。
 *
 * <p>id 取 {@code limbo}(悬置等待态)而非 empty:后者与 Java 惯用词
 * ({@code Optional.empty()} 等)碰撞,进不了引擎禁词表
 * ({@code EngineHasNoModeStringsTest} 整行子串匹配);limbo 全仓零命中、语义与
 * 兜底落点契合。经本 jar SPI 清单注册(装配期 ServiceLoader 半边,
 * {@code MatchModeRegistry#discover});不在任何提供方 modeClaims——第三方
 * {@code unregisterMode} 命中越权响亮拒分支,出厂回退模式天然防摘(31 号决策 H)。
 */
public final class EmptyMatchModeFactory implements MatchModeProvider {

    /** 出厂空模式 id(保留 id:宿主后注册同名由注册闸响亮抛,先到者保留)。 */
    public static final String MODE_ID = "limbo";

    @Override
    public String id() {
        return MODE_ID;
    }

    /** 零组件占位模式:忽略 cfg(出厂空模式零配置面,modes/&lt;id&gt;.yml 仅为免缺失告警)。 */
    @Override
    public MatchMode build(MatchModeConfig cfg) {
        return new MatchMode.Builder(MODE_ID)
                .meta(new MatchModeMeta(false, 0, Integer.MAX_VALUE))
                .build();
    }
}
