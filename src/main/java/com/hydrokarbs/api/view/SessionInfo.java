package com.hydrokarbs.api.view;

import java.util.List;
import java.util.UUID;

/**
 * 会话快照:api 侧不可变 record(07-公共API §7.1/§7.2 值对象行;7.1 施工文档步骤 2)。
 *
 * <p><b>同名消歧</b>:与 {@code engine/port/SessionView}(★29 在用的引擎侧只读视图
 * 接口,07 §7.3)同名不同义——公共面只暴露本 record,引擎契约不属 {@code api/}。
 * api 侧定名 {@code SessionInfo}(与 {@link MatchModeInfo}/{@link MapInfo} 命名对齐;
 * 阶段 7 README §5 登记裁决,2026-10-25 落定)。
 *
 * <p>字段只取会话层事实(§3.1 Session 职责清单:id/名册/当前实例/状态机):
 * <ul>
 *   <li>{@code members} 含断线宽限期成员——roster 全量 uuid(宽限期内仍在册,
 *       §3.1 ConnectionTracker 口径;{@code SessionRoster#markDisconnected} 不移除);</li>
 *   <li>{@code modeId}/{@code mapId}:无当前实例(含切换间隙)为<b>空串</b>
 *       (与 {@code SessionSpec.initialModeId} 空串口径一致,§3.1);</li>
 *   <li>{@code state} 是状态机名字符串({@code RUNNING/ENDING/SWITCHING/DISSOLVING},
 *       {@code SessionState} 封闭枚举的四态,§3.1 状态机图)。</li>
 * </ul>
 *
 * <p><b>快照纪律</b>:转换点一次性取值,集合 {@code List.copyOf}(7.1 要点 1)——
 * 绝不把 {@code Session}/{@code SessionRoster}/{@code MatchModeInstance}/组件实例
 * 放进 record(泄漏活引用 = 跨线程读非线程安全结构,§10.5)。
 * <b>刻意不含</b> team/role——会话成员没有实例语义(14.18:把 SessionMember 当
 * Participant 用;CONTEXT.md「SessionMember」不含 team/role/state)。
 */
public record SessionInfo(String sessionId, String modeId, String mapId,
                          List<UUID> members, String state) {

    public SessionInfo {
        members = List.copyOf(members);
    }
}
