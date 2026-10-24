package com.hydrokarbs.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.event.MatchModeInstanceEvent;
import com.hydrokarbs.engine.event.MatchModeInstanceEventBus;
import com.hydrokarbs.engine.event.MatchModeInstanceEventPort;
import com.hydrokarbs.engine.instance.Participant;
import com.hydrokarbs.engine.port.MatchModeInstancePort;
import com.hydrokarbs.engine.session.EndResult;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.HandoffView;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.map.MapDefinition;

/**
 * 假实例自面(组件单测用):tick 手动推进、end/later/switchTo 全记录、
 * 事件口为真总线。零 Bukkit import(本接口面无 Bukkit 类型)。
 */
public final class FakeSelfPort implements MatchModeInstancePort {

    /** 手动推进的 tick 计数(测试按需 advance)。 */
    public int tickCount;

    /** end 调用记录(每次一条;幂等护栏在真容器,这里全记)。 */
    public final List<EndResult> endCalls = new ArrayList<>();

    /** later 登记记录:(延迟, 任务) 对,不执行。 */
    public final List<int[]> laterDelays = new ArrayList<>();
    public final List<Runnable> laterTasks = new ArrayList<>();

    /** switchTo 调用记录。 */
    public final List<MatchModeSelection> switchCalls = new ArrayList<>();

    /** 真事件总线(发布/订阅行为与容器一致)。 */
    public final MatchModeInstanceEventBus bus = new MatchModeInstanceEventBus();

    private final AttributeBag attrs = new AttributeBag();
    private final Handoff handoff = Handoff.empty();
    private final MatchModeConfig cfg;

    public FakeSelfPort() {
        this((path, def) -> def);
    }

    public FakeSelfPort(MatchModeConfig cfg) {
        this.cfg = cfg;
    }

    /** 推进一拍并返回新值(镜像容器的时钟行为:先推进、后派发)。 */
    public int advanceTick() {
        return ++tickCount;
    }

    @Override
    public List<Participant> participants() {
        return List.of();
    }

    @Override
    public Optional<Participant> participant(UUID uuid) {
        return Optional.empty();
    }

    @Override
    public long tick() {
        return tickCount;
    }

    @Override
    public AttributeBag attrs() {
        return attrs;
    }

    @Override
    public HandoffView handoff() {
        return handoff;
    }

    @Override
    public MapDefinition map() {
        return null;
    }

    @Override
    public MatchModeConfig cfg() {
        return cfg;
    }

    @Override
    public MatchModeInstanceEventPort events() {
        return bus;
    }

    @Override
    public void later(int delayTicks, Runnable task) {
        laterDelays.add(new int[]{delayTicks});
        laterTasks.add(task);
    }

    @Override
    public void end(EndResult result) {
        endCalls.add(result);
    }

    @Override
    public void switchTo(MatchModeSelection target, Handoff payload) {
        switchCalls.add(target);
    }
}
