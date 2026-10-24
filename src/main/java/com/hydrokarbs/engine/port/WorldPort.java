package com.hydrokarbs.engine.port;

import java.util.concurrent.CompletableFuture;

import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.map.MapDefinition;

/**
 * 世界端口:世界的实例化/引用计数/释放(03-会话与模式切换 §3.7)。
 *
 * <p>★ 只有会话内核调用(获取在切换准备阶段、释放在切换完成/解散)——
 * 模式与组件无感,只消费世界句柄上的只读数据。
 * 同一 (map, session) 重复获取幂等返回同句柄;引用计数归零按句柄的
 * {@link ResetStrategy} 处置副本。释放幂等,重复无害。
 */
public interface WorldPort {

    /**
     * 为地图获取就绪世界。同一 map 在不同会话得到独立副本。
     * 模板复制 = 文件 IO(虚拟线程);加载世界 = 主线程。
     */
    CompletableFuture<WorldHandle> acquire(MapDefinition map, SessionId forSession);

    /** 引用计数归零 → 按句柄的 {@link ResetStrategy} 处置副本。幂等。 */
    CompletableFuture<Void> release(WorldHandle handle);
}
