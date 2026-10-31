package com.hydrokarbs.engine.port;

import java.util.List;

import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionMember;

/**
 * 实例侧只读会话视图(★v4-D5/D6;02-核心概念 §2.5.1;12-代码骨架 §12.2 v4 块;
 * 挂在 {@link MatchModeInstancePort#session()} 上)。
 *
 * <p>模式接入会话能力的<b>软接入</b>口(§13.7):实例组件经
 * {@code svc().self().session()} 取本视图,按类型读会话组件的强类型读口;
 * 硬依赖同样以类型表达(★v1.7,25 号修正案:原模式元数据上的字符串能力标签通道
 * 随能力标签机制退场,改由组件 {@code requires/requiresOptional}
 * 类型化依赖 + 装配闸表达)。
 *
 * <p><b>只读是架构纪律 + review 条款</b>(§2.8-决断四、14.29):
 * 实例组件不得写入会话组件。
 * <b>本视图无 {@code events()}</b>——第一版实例组件不得订阅会话事件(未决 §11-Q7)。
 */
public interface SessionView {

    /** 所属会话 id。 */
    SessionId id();

    /** 成员只读快照(不可变列表)。 */
    List<SessionMember> members();

    /** 按类型取<b>声明序第一个</b>可赋值会话组件;无 → null(★模式接入会话能力)。 */
    <T extends SessionComponent> T component(Class<T> type);

    /** 按类型取全部可赋值会话组件(声明序)。 */
    <T extends SessionComponent> List<T> components(Class<T> type);
}
