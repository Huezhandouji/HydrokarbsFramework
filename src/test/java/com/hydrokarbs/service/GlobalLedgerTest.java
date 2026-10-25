package com.hydrokarbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionSpec;

/**
 * 参照服务「上报式」离线单测(1.6 施工步骤 5;修正案 §1 参照实现行)。
 *
 * <p>证明上报式形态可离线验证:收 {@code SessionDissolved} 终局汇总 +
 * {@code sweep()} 只读快照拉取刷新 + stop 后迟到事件不写入。
 * 全部离线直构(依赖经构造器注入),零 Bukkit import。
 */
class GlobalLedgerTest {

    private final SessionEventBus bus = new SessionEventBus();

    /** 存活会话快照源(测试假件:可变列表,模拟会话集合现值)。 */
    private final List<Session> alive = new ArrayList<>();

    /** 会话侧本地账本假件:会话 id → 账本。 */
    private final Map<SessionId, Map<String, Long>> ledgers = new HashMap<>();

    /** 会话服务假件(2.3 接口扩为八方法后改用共享桩:只实现 all(),其余零行为)。 */
    private final SessionService sessions = new com.hydrokarbs.testkit.StubSessionService(() -> List.copyOf(alive));

    private final GlobalLedger ledger =
            new GlobalLedger(bus, sessions, ledgers::get);

    private static SessionId id(String value) {
        return new SessionId(value);
    }

    private static Session session(String value) {
        return new Session(new SessionSpec(id(value), "ledger-probe", java.util.List.of()),
                SessionPolicy.defaults(SessionPolicy.Topology.ROTATION));
    }

    /** 上报通道:收 SessionDissolved → 终局账本并入总账(多会话累加)。 */
    @Test
    void dissolvedSessionFinalLedgerMergesIntoTotal() {
        ledger.start();
        ledgers.put(id("s1"), Map.of("wins", 3L));
        bus.publish(SessionEvent.SessionDissolved.of(id("s1"), DissolveReason.SHUTDOWN));
        assertEquals(Map.of("wins", 3L), ledger.total());

        ledgers.put(id("s2"), Map.of("wins", 4L, "plays", 1L));
        bus.publish(SessionEvent.SessionDissolved.of(id("s2"), DissolveReason.SHUTDOWN));
        assertEquals(Map.of("wins", 7L, "plays", 1L), ledger.total(),
                "第二个会话的终局账本并入并累加(总账只增)");
    }

    /** sweep():对存活会话做只读快照拉取,整体重建实时视图。 */
    @Test
    void sweepRefreshesLiveViewFromSnapshotPull() {
        alive.add(session("s1"));
        alive.add(session("s2"));
        ledgers.put(id("s1"), Map.of("kills", 1L));
        ledgers.put(id("s2"), Map.of("kills", 2L));
        ledger.start();

        ledger.sweep();
        assertEquals(Map.of(id("s1"), Map.of("kills", 1L), id("s2"), Map.of("kills", 2L)),
                ledger.liveView(), "sweep 后实时视图 = 各存活会话的账本快照");

        alive.remove(1);                    // s2 解散离开会话集合(快照现值)
        ledger.sweep();
        assertEquals(Map.of(id("s1"), Map.of("kills", 1L)), ledger.liveView(),
                "sweep 整体重建:已不在快照里的会话条目被丢弃(不缓存陈旧值)");
    }

    /** liveTotals():全服账本 = 总账(终局)+ 各存活会话实时,现算不缓存。 */
    @Test
    void liveTotalsCombinesFinalAndLive() {
        ledgers.put(id("gone"), Map.of("wins", 5L));
        alive.add(session("live"));
        ledgers.put(id("live"), Map.of("wins", 2L));
        ledger.start();
        bus.publish(SessionEvent.SessionDissolved.of(id("gone"), DissolveReason.SHUTDOWN));   // 终局入总账
        ledger.sweep();

        assertEquals(Map.of("wins", 7L), ledger.liveTotals(),
                "全服账本 = 已结转总账(5) + 存活实时(2)");
    }

    /** stop 对称:注销订阅后,总线的迟到事件不再写入总账。 */
    @Test
    void stopUnsubscribesLateEvents() {
        ledger.start();
        ledgers.put(id("s1"), Map.of("wins", 1L));
        bus.publish(SessionEvent.SessionDissolved.of(id("s1"), DissolveReason.SHUTDOWN));
        assertEquals(Map.of("wins", 1L), ledger.total());

        ledger.stop();
        ledgers.put(id("s2"), Map.of("wins", 99L));
        bus.publish(SessionEvent.SessionDissolved.of(id("s2"), DissolveReason.SHUTDOWN));
        assertEquals(Map.of("wins", 1L), ledger.total(),
                "stop 之后的迟到 SessionDissolved 不得并入总账(注销哨兵)");
        assertTrue(ledger.liveView().isEmpty(), "stop 清空实时视图(与 start 对称)");
    }
}
