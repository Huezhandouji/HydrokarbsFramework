package com.hydrokarbs.engine.port;

import java.util.Objects;

/**
 * 入场裁决结果(03-会话与模式切换 §3.2.1 原样)。
 *
 * <p>kind 是结构词汇(引擎认识的全部);reason 是人读归类(如「对局进行中」),
 * 不是可编程契约。
 */
public record JoinDecision(Kind kind, String reason) {

    public JoinDecision {
        Objects.requireNonNull(kind, "kind");
    }

    /** 裁决三种安置:参赛位 / 观战位 / 拒收。 */
    public enum Kind {
        /** 许可入场,安置为参赛位。 */
        ADMIT,
        /** 许可入场,安置为观战位(观战体验归组件层,会话层只记名册)。 */
        SPECTATE,
        /** 拒绝入场(会话不收,名册不登记)。 */
        REJECT
    }
}
