package com.hydrokarbs.engine.mode;

import com.hydrokarbs.engine.session.MatchModeConfig;

/**
 * 模式 SPI 工厂接口(CONTEXT.md 术语表;07-对外 API §7.2)。
 *
 * <p>聚合根是 final 类、build 后不可追加组件——第三方经本接口注册模式;
 * 改模式 = 注册新 id,不提供运行期装饰入口(A9)。
 * 发现与注册(ServiceLoader + ServicesManager 双发现的 {@code MatchModeRegistry})
 * 归阶段 2(2.2);本阶段只立接口。
 */
public interface MatchModeProvider {

    /** 模式 id(注册表唯一键)。 */
    String id();

    /** 用给定配置造聚合根(每模式一份 {@code modes/<id>.yml} 视图)。 */
    MatchMode build(MatchModeConfig cfg);
}
