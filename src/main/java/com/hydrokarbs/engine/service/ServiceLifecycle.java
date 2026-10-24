package com.hydrokarbs.engine.service;

/**
 * 进程级服务的可选生命周期(18 号修正案 D4/§3;施工计划 1.6)。
 *
 * <p>纯无状态服务(纯门面类)不必实现;需要「启动时登记、关停时回收」的服务实现之:
 * 典型用途是在 {@link #start()} 里订阅会话事件总线,并在 {@link #stop()} 里注销订阅。
 *
 * <p><b>实现者的硬义务:{@link #stop()} 与 {@link #start()} 严格对称</b>——
 * start 建立的一切(订阅、登记、可见副作用)由 stop 全部撤销。关停的逆序、
 * 逐个异常隔离、幂等(连调多次 stop 恰执行一次)由注册表统一保证(★38),
 * 实现者只管自己的对称性。
 */
public interface ServiceLifecycle {

    /** 启动:装配成功后由注册表按拓扑序调用(每个服务至多一次)。 */
    default void start() {
    }

    /** 停止:关停/装配失败回滚时由注册表逆序调用;与 start 严格对称。 */
    default void stop() {
    }
}
