package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.session.SessionId;

/**
 * 参照服务「拉取式」离线单测(1.6 施工步骤 5;修正案 §1 参照实现行)。
 *
 * <p>证明拉取式形态可离线验证:订阅三类生命周期事件维护「现存会话」视图 +
 * 查询口一律返回只读快照 + stop 后迟到事件不生效。零 Bukkit import。
 */
class GlobalBoardTest {

    private final SessionEventBus bus = new SessionEventBus();
    private final GlobalBoard board = new GlobalBoard(bus);

    private static SessionId id(String value) {
        return new SessionId(value);
    }

    /** 订阅通道:创建登记、切换完成更新当前模式、解散除名。 */
    @Test
    void boardTracksLiveSessionsThroughLifecycleEvents() {
        board.start();
        bus.publish(new SessionEvent.SessionCreated(id("s1")));
        bus.publish(new SessionEvent.SessionCreated(id("s2")));
        assertEquals(java.util.Set.of(id("s1"), id("s2")), board.liveSessions());

        bus.publish(new SessionEvent.ModeSwitchCompleted(id("s1"), "alpha", "beta"));
        assertEquals(Map.of(id("s1"), "beta", id("s2"), ""), board.currentModes(),
                "切换完成事件更新该会话的当前模式;未收到切换的会话保持空串");

        bus.publish(new SessionEvent.SessionDissolved(id("s2")));
        assertEquals(java.util.Set.of(id("s1")), board.liveSessions(), "解散除名");
        assertEquals("beta", board.modeOf(id("s1")).orElseThrow());
        assertTrue(board.modeOf(id("s2")).isEmpty(), "已解散会话的查询 → empty");
    }

    /** 查询口只读快照:返回不可变拷贝,改动它不影响内部视图。 */
    @Test
    void viewIsReadonlySnapshotCopy() {
        board.start();
        bus.publish(new SessionEvent.SessionCreated(id("s1")));
        Map<SessionId, String> view = board.currentModes();
        assertThrows(UnsupportedOperationException.class,
                () -> view.put(id("s9"), "rogue"),
                "currentModes() 返回不可变快照拷贝");

        bus.publish(new SessionEvent.ModeSwitchCompleted(id("s1"), "alpha", "beta"));
        assertEquals("", view.get(id("s1")),
                "外部持有的旧快照不受后续事件影响(现取快照语义)");
        assertEquals("beta", board.currentModes().get(id("s1")));
    }

    /** stop 对称:清空视图,之后的迟到事件不再写入。 */
    @Test
    void stopClearsViewAndIgnoresLateEvents() {
        board.start();
        bus.publish(new SessionEvent.SessionCreated(id("s1")));
        board.stop();

        assertTrue(board.liveSessions().isEmpty(), "stop 清空视图(与 start 对称)");
        bus.publish(new SessionEvent.SessionCreated(id("s2")));
        bus.publish(new SessionEvent.ModeSwitchCompleted(id("s2"), "alpha", "beta"));
        assertTrue(board.liveSessions().isEmpty(),
                "stop 之后的迟到事件不得写入视图(注销哨兵)");
    }
}
