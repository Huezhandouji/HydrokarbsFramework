package com.hydrokarbs.api;

/**
 * 会话事件订阅句柄(★v1.1-D3;07-公共API §7.1 第二代码块;7.5 施工文档步骤 3):
 * {@link #close()} 注销本订阅,注销后不再收到任何会话事件。
 *
 * <p><b>句柄纪律(14.38)</b>:句柄只在插件实例字段中持有,{@code onDisable} 显式
 * close——reload 后旧句柄残留 = 重复事件与句柄泄漏;框架侧
 * {@code PluginDisableEvent} 兜底只摘注册物(模式/能力标签/贡献/地图),
 * <b>订阅句柄的回收是消费方自己的义务</b>(07 §7.5 示例一的 {@code sub} 字段先例)。
 *
 * <p>幂等:重复 {@code close()} 无害、不抛(镜像 {@code SessionEventBus.Subscription}
 * 既有口径)。
 */
@FunctionalInterface
public interface EventSubscription extends AutoCloseable {

    /** 注销本订阅(幂等;重复 close 无害,不抛;14.38)。 */
    @Override
    void close();
}
