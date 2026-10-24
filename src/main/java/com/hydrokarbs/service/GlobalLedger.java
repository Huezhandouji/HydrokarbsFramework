package com.hydrokarbs.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.service.ServiceLifecycle;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionService;

/**
 * 参照服务一:<b>上报式</b>——会话侧出本地账本,进程级服务收终局快照汇总
 * (18 号修正案 §3 参照服务骨架;选型④「会话本地权威 + 服务汇总」,状态唯一不破)。
 *
 * <p>两条数据面:<b>总账</b>({@link #total()})只收已解散会话的终局账本,
 * 经订阅 {@code SessionDissolved} 事件汇入;{@link #sweep()} 对存活会话做
 * <b>只读快照拉取</b>(遍历 {@link SessionService#all()},D5 通道②),刷新
 * {@link #liveView()} 实时视图;{@link #liveTotals()} = 总账 + 存活实时(现算)。
 *
 * <p>纪律(14.31/D5):快照通道「只读快照、现取不缓存」——拉到的账本一律拷贝成
 * 值快照,不持有会话侧的可变集合;永不持有活实例/活组件引用。订阅回调经
 * 注销哨兵包裹:{@link #stop()} 之后总线里的迟到回调不再写入任何状态
 * (D5 通道①「订阅句柄在 stop 注销」——总线逐条注销面归 2.6,本阶段以哨兵实现
 * 同等语义,登记于施工偏差)。
 *
 * <p>依赖一律经构造器注入(镜像 L0 工厂闭包捕获口径),服务间依赖只能经
 * {@code ServiceSpec#requires} 声明、由注册表注入,禁止互 new(14.34)。
 * {@link #stop()} 与 {@link #start()} 严格对称:start 建立「哨兵 + 订阅」,
 * stop 注销订阅并清实时视图;总账是终局汇总,随服务对象整体生灭
 * (服务生命周期 = 进程生命周期,关停后无消费面)。
 */
public final class GlobalLedger implements ServiceLifecycle {

    /** 会话侧本地账本的只读拉取面(参照契约;阶段 2 由会话级组件提供实现)。 */
    @FunctionalInterface
    public interface SessionLedgerSource {

        /** 指定会话的本地账本快照(条目 → 数值;未知会话返回空映射)。 */
        Map<String, Long> ledgerOf(SessionId session);
    }

    private final SessionEventBus bus;
    private final SessionService sessions;
    private final SessionLedgerSource source;

    /** 总账:已解散会话的终局汇总(只增;值快照并入)。 */
    private final Map<String, Long> total = new LinkedHashMap<>();

    /** 实时视图:存活会话 id → 该会话本地账本的值快照(sweep 重建)。 */
    private final Map<SessionId, Map<String, Long>> liveView = new LinkedHashMap<>();

    /** 注销哨兵:false 后总线里的迟到回调一律跳过。 */
    private volatile boolean active;

    public GlobalLedger(SessionEventBus bus, SessionService sessions, SessionLedgerSource source) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.source = Objects.requireNonNull(source, "source");
    }

    /** 订阅终局事件(上报通道:D5 通道①)。 */
    @Override
    public void start() {
        active = true;
        bus.subscribe(SessionEvent.SessionDissolved.class, event -> {
            if (active) {
                onSessionDissolved(event);
            }
        });
    }

    /** 注销订阅(哨兵)、清实时视图——与 start 严格对称。 */
    @Override
    public void stop() {
        active = false;
        liveView.clear();
    }

    /** 收终局账本并入总账(骨架钦定的公开入口;订阅回调转调本方法,测试可直调)。 */
    public void onSessionDissolved(SessionEvent.SessionDissolved event) {
        mergeInto(total, snapshotOf(event.session()));
        liveView.remove(event.session());
    }

    /** 对存活会话做只读快照拉取,整体重建实时视图(丢弃已解散会话的陈旧条目)。 */
    public void sweep() {
        Map<SessionId, Map<String, Long>> fresh = new LinkedHashMap<>();
        for (Session session : sessions.all()) {
            fresh.put(session.id(), snapshotOf(session.id()));
        }
        liveView.clear();
        liveView.putAll(fresh);
    }

    /** 总账只读快照(已解散会话的终局汇总)。 */
    public Map<String, Long> total() {
        return Map.copyOf(total);
    }

    /** 实时视图只读快照(最近一次 sweep 拉到的各存活会话账本)。 */
    public Map<SessionId, Map<String, Long>> liveView() {
        Map<SessionId, Map<String, Long>> copy = new LinkedHashMap<>();
        liveView.forEach((id, ledger) -> copy.put(id, Map.copyOf(ledger)));
        return copy;
    }

    /** 全服账本现算:总账 + 各存活会话实时(不缓存,每次调用聚合一次)。 */
    public Map<String, Long> liveTotals() {
        Map<String, Long> combined = new LinkedHashMap<>(total);
        liveView.values().forEach(ledger -> mergeInto(combined, ledger));
        return Map.copyOf(combined);
    }

    /** 拉取并拷贝:不持有来源侧可变集合(「现取不缓存」纪律的机械面)。 */
    private Map<String, Long> snapshotOf(SessionId session) {
        return Map.copyOf(source.ledgerOf(session));
    }

    private static void mergeInto(Map<String, Long> target, Map<String, Long> ledger) {
        ledger.forEach((entry, value) -> target.merge(entry, value, Long::sum));
    }
}
