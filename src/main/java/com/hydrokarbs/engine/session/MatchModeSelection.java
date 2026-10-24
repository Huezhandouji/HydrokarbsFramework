package com.hydrokarbs.engine.session;

import java.util.Optional;

/**
 * 目标模式选择:切换指令的「下一站」描述(03-会话与模式切换 §3.2)。
 *
 * <p>mapId 为空 → 按 mode 支持的 tag 经地图挑选策略(RANDOM/ROTATION)挑一张。
 * 引擎对「下一站」本身零理解——只执行本 record;投票、轮换、管理员指定
 * 全部是产出本类型的不同上游,引擎不区分。
 *
 * <p>字段注释(空 = 无显式地图):
 * <ul>
 *   <li>{@code modeId}——目标模式 id;</li>
 *   <li>{@code mapId}——可选的指定地图 id;</li>
 *   <li>{@code force}——显式豁免人数窗下限等防护(外部调用方的显式选择,走 review 条款)。</li>
 * </ul>
 */
public record MatchModeSelection(String modeId, Optional<String> mapId, boolean force) {

    /** 单参工厂:只指定模式,不带地图、不带豁免(03 §3.2 原样)。 */
    public static MatchModeSelection of(String modeId) {
        return new MatchModeSelection(modeId, Optional.empty(), false);
    }
}
