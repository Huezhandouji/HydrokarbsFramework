package com.hydrokarbs.engine.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.hydrokarbs.engine.instance.MatchModeInstance;

/**
 * 会话:跨模式常驻的玩家容器(名册/当前实例/状态机,03-会话与模式切换 §3.1)。
 *
 * <p><b>编译壳——状态机/切换/解散归阶段 2</b>(施工计划 1.3「engine/session 编译壳」);
 * 本阶段提供实例容器与节拍器可编译可测的最小落面:
 * <ul>
 *   <li>{@link #instanceEnded}:实例结束请求的收口(§12.2 {@code requestEnd} 的通知落点),
 *       本阶段仅记录请求——清场与切换由阶段 2 的节拍驱动接管;</li>
 *   <li>{@link #runPendingSwitch} / {@link #tickCurrentInstance} / {@link #idleTimers} /
 *       {@link #quarantine}:空实现,阶段 2 接管(§3.1 状态机与 §12.2 tickAll 驱动序)。</li>
 * </ul>
 *
 * <p>方法一律非 final,供测试桩覆写计数;字段与完整方法面由阶段 2 按 §3.2 立出。
 */
public class Session {

    private final SessionId id;
    private final SessionRoster roster;
    private final List<EndNotice> endNotices = new ArrayList<>();
    private MatchModeInstance currentInstance;    // 0..1;维护归阶段 2

    public Session(SessionId id, SessionRoster roster) {
        this.id = Objects.requireNonNull(id, "id");
        this.roster = Objects.requireNonNull(roster, "roster");
    }

    public SessionId id() {
        return id;
    }

    public SessionRoster roster() {
        return roster;
    }

    /** 当前实例(本阶段恒为 null——挂接归阶段 2 的切换执行段)。 */
    public MatchModeInstance currentInstance() {
        return currentInstance;
    }

    /**
     * 实例结束请求的收口(§12.2 {@code requestEnd}):本阶段仅记录请求,
     * 不清场、不切换——ticker 下一 tick 处置(§3.1「结束判定不立即清场」)。
     */
    public void instanceEnded(MatchModeInstance instance, EndResult result) {
        endNotices.add(new EndNotice(instance, result));
    }

    /** 待处理的模式切换(阶段 2:切换的唯一驱动点是节拍器调用本方法,§3.5)。 */
    public void runPendingSwitch() {
        // 阶段 2 接管
    }

    /** 驱动当前实例一拍(阶段 2:转调当前实例的派发方法)。 */
    public void tickCurrentInstance() {
        // 阶段 2 接管
    }

    /** 会话级节律(阶段 2:断线宽限等内核清点)。 */
    public void idleTimers() {
        // 阶段 2 接管
    }

    /** 会话故障隔离(阶段 2:强制切回退/解散)。 */
    public void quarantine() {
        // 阶段 2 接管
    }

    /** 已收到的结束请求记录(诊断用;完整处置归阶段 2)。 */
    List<EndNotice> endNotices() {
        return List.copyOf(endNotices);
    }

    /** 结束请求记录:(实例, 结果)。 */
    record EndNotice(MatchModeInstance instance, EndResult result) {
    }
}
