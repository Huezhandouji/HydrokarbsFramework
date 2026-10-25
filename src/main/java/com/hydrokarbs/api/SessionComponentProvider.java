package com.hydrokarbs.api;

import java.util.List;

import com.hydrokarbs.engine.component.SessionComponent;

/**
 * 会话组件全局默认贡献 SPI(★v1.1-D14 通道一;07-公共API §7.1 第二代码块;
 * 07 §7.3「受限开放」):插件可「拼」会话——提供宿主级会话策略
 * (自定义重连/宽限/准入记账等),<b>模式仍无权贡献</b>(v4-D4 不变)、
 * 无运行期增删(贡献只发生在会话创建时,装配点唯一不变)。
 *
 * <p><b>注册(D14;07 §7.3 表)</b>:本插件 onEnable 里
 * {@code getServer().getServicesManager().register(SessionComponentProvider.class,
 * provider, this, ServicePriority.Normal)}——与 {@code MatchModeProvider} 同一
 * ServicesManager 机制。框架经 {@code ServiceRegisterEvent} 收录时做<b>干跑合并校验</b>
 * (组件 id 查重/必需依赖可满足):坏 provider SEVERE 拒收录、会话照常;
 * 收录后<b>所有</b>后续 {@code createSession} 合并其贡献(注册序)。
 *
 * <p><b>卸载(14.38/D14)</b>:provider 插件 disable → 框架兜底摘除
 * ({@code ServiceUnregisterEvent} 半边 + {@code PluginDisableEvent} 第二道),
 * 后续会话不再合并;已装配会话不受影响(无运行期拆卸,随 dissolve 自然消亡)。
 *
 * <p>防线四件套(D14;7.5 关键实现要点):注册期干跑校验(本面收录闸)→ 装配期
 * {@code verifyDependencies} → 运行期单组件 quarantine(★30,会话不解散)→
 * 预算告警(观测实现落 8.1,挂钩点留给 EngineTicker,7.5 不实现计时)。
 */
public interface SessionComponentProvider {

    /**
     * 本提供方贡献的会话组件描述符(每次收录调用读取;返回列表元素不可为 null)。
     * 组件 id 由供给面类型名推导(与引擎装配路径同源)——两个贡献同类型组件 =
     * id 冲突,干跑校验响亮拒收录后者。
     */
    List<SessionComponent.Specification<?>> sessionComponents();

    /**
     * 声明本提供方要求的最低 API_VERSION(D7 版本协商;镜像
     * {@code MatchModeProvider#requiredApiVersion},只增默认方法不破既有实现)。
     * 默认 1 = 不消费任何版本增量。
     */
    default int requiredApiVersion() {
        return 1;
    }
}
