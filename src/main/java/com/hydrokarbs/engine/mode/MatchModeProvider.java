package com.hydrokarbs.engine.mode;

import com.hydrokarbs.engine.session.MatchModeConfig;

/**
 * 模式 SPI 工厂接口(CONTEXT.md 术语表;07-公共API §7.2;13-扩展步骤 §13.4;
 * 7.2 施工文档步骤 4)。
 *
 * <p><b>注册契约:注册工厂,不是注册成品</b>——聚合根 {@link MatchMode} 是
 * final 类不是接口,Builder 即声明语言,本接口即 SPI:第三方插件在 onEnable 里
 * {@code getServer().getServicesManager().register(MatchModeProvider.class,
 * factory, this, ServicePriority.Normal)},框架经 ServicesManager 事件收录并
 * {@code build()}(内含注册前闸 verifyDependencies,坏声明进不了注册表,§2.6)。
 * 发现与注册:ServiceLoader(本 jar)+ ServicesManager(跨插件)双发现的
 * {@link MatchModeRegistry}。参照实现:{@code examples/TheirPlugin}(7.2)。
 *
 * <p><b>A9 三限制(刻意限制,§0.2-A9/07 §7.2「v3 补充的刻意限制」)</b>:
 * <ol>
 *   <li>聚合根 {@code build()} 后不可变;</li>
 *   <li>不能给已注册模式追加组件或改组件表;</li>
 *   <li>要变体就注册一个新 id——杜绝运行期装饰带来的加载顺序地狱。</li>
 * </ol>
 *
 * <p><b>第三方依赖白名单(§13.4 约束段;7.2 施工文档步骤 4)</b>:工厂与
 * 其组件只准依赖 ① {@code com.hydrokarbs.api}(公共面,7.1 起在册);
 * ② engine 契约(本接口与 {@code engine.mode}/{@code engine.session} 值类型、
 * 两级组件基类等 07 §7.2 白名单内类型);③ L3 组件的公开
 * {@code Specification}(★v1.3 历史口径注:原「禁 import data/**」随 21-D1
 * 持久化整体出框架而空转,包已不存在基线)。<b>其组件抛异常只隔离自己的实例</b>
 * (§3.1 quarantine:实例 tick 的 catch → {@code end(nowhere())} → 会话切回退,
 * §12.2),不会拖垮框架。
 */
public interface MatchModeProvider {

    /** 模式 id(注册表唯一键)。 */
    String id();

    /** 用给定配置造聚合根(每模式一份 {@code modes/<id>.yml} 视图)。 */
    MatchMode build(MatchModeConfig cfg);

    /**
     * 声明本工厂要求的最低 API_VERSION(★v1.1-D7 版本协商;7.5 施工文档步骤 4):
     * 默认 1 = 不消费任何版本增量(只增默认方法,不破既有实现);框架在收录点
     * 记录每个 Provider 的声明要求(注册处打点,14.36)——消费方侧对应
     * {@code onEnable} 首行的 {@code MiniGameApi.API_VERSION} 检查(拒载不崩服)。
     */
    default int requiredApiVersion() {
        return 1;
    }
}
