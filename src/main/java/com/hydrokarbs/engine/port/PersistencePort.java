package com.hydrokarbs.engine.port;

import java.util.concurrent.CompletableFuture;

/**
 * 持久化端口(最小落点,施工计划 1.2;14-陷阱清单 §14.8 的收口)。
 *
 * <p>成员按消费方(阶段 5 持久化)扩充、只增不改,已有签名不改;
 * 扩充须过设计审查(与 svc 三成员同一纪律)。仓储方法面归阶段 5 按需立出。
 */
public interface PersistencePort {

    /** 立即冲刷全部待写数据(关停/切换收口时调用;完成即数据落定)。 */
    CompletableFuture<Void> flushNow();
}
