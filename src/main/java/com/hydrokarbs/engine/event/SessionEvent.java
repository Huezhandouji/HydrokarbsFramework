package com.hydrokarbs.engine.event;

import java.util.UUID;

import com.hydrokarbs.engine.session.SessionId;

/**
 * 会话事件根:会话生命周期的公开信号(03-会话与模式切换 §3.1 事件行)。
 *
 * <p>七个事件(字段最小集,语义归阶段 2):创建/成员进出与重连/切换起讫/解散。
 * 给外部订阅者与跨切面逻辑用;总线骨架与实例事件总线同一套
 * (按事件类型 + 优先级调用订阅者、单订阅者异常隔离、close 清除,11-事件流 §11.1/§11.3)。
 * ★ 不是 Bukkit Event、不进服务端总线。
 *
 * <p>封闭子族 = 本文件的七个嵌套 record(permits 省略,由编译器按同文件推断)。
 */
public sealed interface SessionEvent {

    /** 会话创建完成(首个实例装配之前)。 */
    record SessionCreated(SessionId session) implements SessionEvent {
    }

    /** 成员加入名册。 */
    record MemberJoined(SessionId session, UUID member) implements SessionEvent {
    }

    /** 成员离开名册(退出/被踢)。 */
    record MemberLeft(SessionId session, UUID member) implements SessionEvent {
    }

    /** 断线成员在宽限期内重连。 */
    record MemberReconnected(SessionId session, UUID member) implements SessionEvent {
    }

    /** 模式切换开始(携带 from/to 模式 id,值由发布方传入)。 */
    record ModeSwitchStarted(SessionId session, String fromMode, String toMode) implements SessionEvent {
    }

    /** 模式切换完成(携带 from/to 模式 id,值由发布方传入)。 */
    record ModeSwitchCompleted(SessionId session, String fromMode, String toMode) implements SessionEvent {
    }

    /** 会话解散(终态)。 */
    record SessionDissolved(SessionId session) implements SessionEvent {
    }
}
