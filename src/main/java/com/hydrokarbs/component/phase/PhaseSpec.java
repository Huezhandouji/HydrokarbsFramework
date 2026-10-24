package com.hydrokarbs.component.phase;

import java.util.Objects;
import java.util.function.Predicate;

import com.hydrokarbs.engine.instance.MatchModeInstanceView;

/**
 * 阶段表条目:纯数据(施工计划 1.5;06-共享组件 §6.1-#4)。
 *
 * <p>阶段表只有两种条目(领域术语表「引擎三迁组件」为权威——仅 Timed/Await,
 * 不设第三种):
 * <ul>
 *   <li>{@link Kind#AWAIT}:等待型——携带 {@code entryWhen} 谓词,为真即离开本阶段
 *       转入下一阶段(如「成员数达到下限」);{@code durationTicks} 必须为 null;</li>
 *   <li>{@link Kind#TIMED}:计时型——携带 {@code durationTicks},到点转入下一阶段;
 *       {@code entryWhen} 必须为 null。</li>
 * </ul>
 *
 * <p>构造口径即签名:两种条目各占一半字段,给错字段在紧凑构造器响亮失败——
 * 阶段是数据不是行为,模式差异只体现在 Builder 声明这张表时(14-陷阱清单 §14.12)。
 */
public record PhaseSpec(String id, Kind kind, Predicate<MatchModeInstanceView> entryWhen, Integer durationTicks) {

    public PhaseSpec {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("阶段 id 不能为空");
        }
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case AWAIT -> {
                if (entryWhen == null) {
                    throw new IllegalArgumentException("阶段 " + id + ":AWAIT 条目必须声明 entryWhen 谓词");
                }
                if (durationTicks != null) {
                    throw new IllegalArgumentException("阶段 " + id + ":AWAIT 条目不得携带 durationTicks");
                }
            }
            case TIMED -> {
                if (durationTicks == null || durationTicks <= 0) {
                    throw new IllegalArgumentException("阶段 " + id + ":TIMED 条目必须携带正数 durationTicks");
                }
                if (entryWhen != null) {
                    throw new IllegalArgumentException("阶段 " + id + ":TIMED 条目不得携带 entryWhen 谓词");
                }
            }
        }
    }

    /** 阶段条目种类,只有两枚(等待型 / 计时型)。 */
    public enum Kind {
        /** 等待型:entryWhen 为真即转入下一阶段。 */
        AWAIT,
        /** 计时型:durationTicks 耗尽即转入下一阶段。 */
        TIMED
    }
}
