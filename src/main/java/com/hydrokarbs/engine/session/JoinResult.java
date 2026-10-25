package com.hydrokarbs.engine.session;

/**
 * 入场结果(03-会话与模式切换 §3.2.1 原样;与 {@link SwitchResult} 同住会话层,
 * 一种结果格式贯穿):拒绝原因走 reason/detail,不是可编程契约。
 *
 * <p>{@code REJECT} 裁决下会话不收(名册不登记);{@code ADMIT}/{@code SPECTATE}
 * 都登记名册,差别只在成为参赛者的安置方式(参赛位/观战位,观战体验归组件层)。
 */
public sealed interface JoinResult {

    /** 已加入(携带会话 id)。 */
    record Joined(SessionId session) implements JoinResult {
    }

    /** 被拒:reason 是简短归类(人读),detail 是人读原因清单(如「对局进行中,回合结束后可进」)。 */
    record Rejected(String reason, String detail) implements JoinResult {
    }
}
