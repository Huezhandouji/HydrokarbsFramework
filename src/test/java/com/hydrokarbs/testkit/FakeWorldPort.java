package com.hydrokarbs.testkit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.bukkit.World;

import com.hydrokarbs.engine.port.WorldHandle;
import com.hydrokarbs.engine.port.WorldPort;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.map.MapDefinition;

/**
 * 世界端口假件(2.3 切换链路离线测试):获取行为可注入(即时成功/注入失败——
 * 超时与失败共用同一完成回调分支,注入 {@code TimeoutException} 即覆盖超时路径);
 * 释放为空操作;会话销毁计数并按序记录被销毁的会话 id(解散触发断言用)。
 * 世界对象用动态代理占位(测试类型签名引用 Bukkit 类型、传代理不碰
 * Bukkit 静态——句柄只存引用不调方法)。
 */
public final class FakeWorldPort implements WorldPort {

    /** 动态代理世界(句柄与实例只持引用;方法默认应答零开销)。 */
    public static World proxyWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "fake-world";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "fake-world-proxy";
                    default -> null;
                });
    }

    private volatile Function<MapDefinition, CompletableFuture<WorldHandle>> behavior;

    /** 获取计数(引用/释放断言用)。 */
    public int acquireCount;
    /** 释放计数。 */
    public int releaseCount;
    /** 会话销毁计数(解散触发 discardSession 断言用)。 */
    public int discardCount;
    /** 被销毁的会话 id(按调用序记录;既有 acquire/release 计数不变)。 */
    public final List<SessionId> discardedSessions = new ArrayList<>();

    private FakeWorldPort(Function<MapDefinition, CompletableFuture<WorldHandle>> behavior) {
        this.behavior = behavior;
    }

    /** 即时成功(每次新句柄,实例名 = mapId-会话短码)。 */
    public static FakeWorldPort immediate() {
        return new FakeWorldPort(map -> CompletableFuture.completedFuture(
                new WorldHandle(proxyWorld(), map.id() + "-copy", map, map.resetStrategy())));
    }

    /** 即时失败(注入异常;超时路径共用本分支,传 TimeoutException 即超时等价物)。 */
    public static FakeWorldPort failing(Throwable cause) {
        return new FakeWorldPort(map -> CompletableFuture.failedFuture(cause));
    }

    /** 运行期切换为失败(首切实例就位后拦目标切换的 A6 注入口)。 */
    public void failFromNowOn(Throwable cause) {
        behavior = map -> CompletableFuture.failedFuture(cause);
    }

    @Override
    public CompletableFuture<WorldHandle> acquire(MapDefinition map, SessionId forSession) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(forSession, "forSession");
        acquireCount++;
        return behavior.apply(map);
    }

    @Override
    public CompletableFuture<Void> release(WorldHandle handle) {
        releaseCount++;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> discardSession(SessionId session) {
        Objects.requireNonNull(session, "session");
        discardCount++;
        discardedSessions.add(session);
        return CompletableFuture.completedFuture(null);
    }
}
