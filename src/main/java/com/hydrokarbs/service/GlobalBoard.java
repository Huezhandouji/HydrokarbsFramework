package com.hydrokarbs.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.service.ServiceLifecycle;
import com.hydrokarbs.engine.session.SessionId;

/**
 * 参照服务二:<b>拉取式</b>——零权威状态的全局看板
 * (18 号修正案 §3 参照服务骨架;选型①「只读快照 + 事件,零侵入」)。
 *
 * <p>「零状态」指零业务权威状态(与上报式的总账对仗):本类只维护
 * 生命周期事件驱动的最小值记号(会话 id → 当前模式 id 字符串),全部是值拷贝,
 * <b>永不持有活实例/活组件引用</b>(14.31);查询口一律现取——每次调用返回
 * 新拷贝,内部视图不外露。深展示数据(成员/进度等)在阶段 2 的只读实例面就绪后
 * 改为现取快照(D5 通道②),本阶段视图仅由通道①(订阅)驱动:
 * {@code SessionCreated} 登记、{@code SessionDissolved} 除名、
 * {@code ModeSwitchCompleted} 更新当前模式。
 *
 * <p>订阅回调经注销哨兵包裹:{@link #stop()} 之后总线里的迟到回调不再写入
 * (D5 通道①「订阅句柄在 stop 注销」——总线逐条注销面归 2.6,本阶段以哨兵
 * 实现同等语义,登记于施工偏差)。
 *
 * <p>依赖经构造器注入(镜像 L0 工厂闭包捕获口径);服务间依赖只能经
 * {@code ServiceSpec#requires} 声明、由注册表注入,禁止互 new(14.34)。
 */
public final class GlobalBoard implements ServiceLifecycle {

    private final SessionEventBus bus;

    /** 现存会话视图:会话 id → 当前模式 id(空串 = 已创建、尚未收到首个切换完成事件)。 */
    private final Map<SessionId, String> currentModes = new LinkedHashMap<>();

    /** 注销哨兵:false 后总线里的迟到回调一律跳过。 */
    private volatile boolean active;

    public GlobalBoard(SessionEventBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    /** 订阅生命周期事件,维护现存会话视图(D5 通道①)。 */
    @Override
    public void start() {
        active = true;
        bus.subscribe(SessionEvent.SessionCreated.class, event -> {
            if (active) {
                currentModes.put(event.session(), "");
            }
        });
        bus.subscribe(SessionEvent.ModeSwitchCompleted.class, event -> {
            if (active) {
                currentModes.put(event.session(), event.toMode());
            }
        });
        bus.subscribe(SessionEvent.SessionDissolved.class, event -> {
            if (active) {
                currentModes.remove(event.session());
            }
        });
    }

    /** 注销订阅(哨兵)、清空视图——与 start 严格对称。 */
    @Override
    public void stop() {
        active = false;
        currentModes.clear();
    }

    /** 现存会话集合(现取快照,不可变)。 */
    public Set<SessionId> liveSessions() {
        return Set.copyOf(currentModes.keySet());
    }

    /** 现存会话 → 当前模式 id(现取快照,不可变;调用方改它不影响内部视图)。 */
    public Map<SessionId, String> currentModes() {
        return Map.copyOf(currentModes);
    }

    /** 单会话当前模式(尚未收到切换完成事件 → 空串;未知会话 → empty)。 */
    public Optional<String> modeOf(SessionId session) {
        String mode = currentModes.get(session);
        return mode == null ? Optional.empty() : Optional.of(mode);
    }
}
