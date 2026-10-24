package com.hydrokarbs.engine.port;

import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

/**
 * 平台端口:宿主平台原语的最小落点(施工计划 1.2;节拍器/调度依赖 Plugin 类型)。
 *
 * <p>成员按消费方(阶段 2 会话/节拍器接线)扩充、只增不改;
 * 扩充须过设计审查(与 svc 三成员同一纪律)。
 */
public interface PlatformPort {

    /** 框架日志口。 */
    Logger logger();

    /** 宿主插件句柄(调度/资源/数据目录的原语入口)。 */
    Plugin plugin();

    /**
     * 一次性主线程延迟任务(施工计划 1.3 增补:实例侧 {@code later()} 的调度口,
     * 经 {@code EngineServices.platform()} 注入)。
     * 返回可取消句柄({@code close} 等价取消),由实例容器登记到发起组件名下、
     * 随实例 close 统一回收;离线测试注入「记录并即时执行」的假实现。
     */
    AutoCloseable scheduleOnce(long delayTicks, Runnable task);
}
