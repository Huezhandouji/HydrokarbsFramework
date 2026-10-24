package com.hydrokarbs.component.endgate;

import java.util.Objects;

/**
 * 玩法结局词汇:胜/平/续/中止 + 可空胜者 + 原因(03-会话与模式切换 §3.4 第一代码块)。
 *
 * <p>★ 住 component/endgate,引擎零认知——引擎的结束货币只有「去向」
 * ({@code EndResult}),不枚举「游戏有哪些结局」(跑酷/极简大厅没有胜平)。
 * {@code winnerId} 可空(平局/续场/中止无胜者),以 javadoc 表达可空性。
 */
public record MatchOutcome(Kind kind, String winnerId, String reason) {

    public MatchOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
    }

    /** 玩法结局词汇(内容层共享词汇,四枚)。 */
    public enum Kind {
        /** 分出胜负(winnerId 通常非空)。 */
        WIN,
        /** 平局。 */
        DRAW,
        /** 正常续场(如倒计时到点切下一模式,不是终局)。 */
        NEXT,
        /** 中止(人数不足/管理命令等非正常完结)。 */
        ABORTED
    }
}
