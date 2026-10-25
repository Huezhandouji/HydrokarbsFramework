package com.hydrokarbs.testkit;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.engine.session.SwitchResult;

/**
 * 会话服务测试桩(2.3 接口扩为八方法后立):{@code all()} 委托注入的会话集合源
 * (节拍器/参照服务消费面),其余方法零行为——服务层与节拍器测试不再各自手写全量实现。
 */
public class StubSessionService implements SessionService {

    private final Supplier<Collection<Session>> source;

    public StubSessionService(Supplier<Collection<Session>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Collection<Session> all() {
        return source.get();
    }

    @Override
    public Session create(SessionSpec spec) {
        throw new UnsupportedOperationException("测试桩不消费");
    }

    @Override
    public Optional<Session> sessionOf(UUID player) {
        return Optional.empty();
    }

    @Override
    public Optional<Session> session(SessionId id) {
        return Optional.empty();
    }

    @Override
    public CompletableFuture<SwitchResult> switchMode(SessionId id, MatchModeSelection target, Handoff payload) {
        throw new UnsupportedOperationException("测试桩不消费");
    }

    @Override
    public JoinResult join(SessionId id, UUID player) {
        throw new UnsupportedOperationException("测试桩不消费");
    }

    @Override
    public void leave(UUID player, LeaveReason reason) {
        // 测试桩不消费
    }

    @Override
    public void dissolve(SessionId id, DissolveReason reason) {
        // 测试桩不消费
    }
}
