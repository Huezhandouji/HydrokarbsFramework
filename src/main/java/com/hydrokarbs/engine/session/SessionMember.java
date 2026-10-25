package com.hydrokarbs.engine.session;

import java.util.Objects;
import java.util.UUID;

/**
 * 会话级成员:uuid、连接态、加入时间(03-会话与模式切换 §3.1 职责清单「名册」行)。
 *
 * <p>★ 刻意只有三件。字段取舍的自问句(14-陷阱清单 §14.18「两层成员」):
 * <b>「这个数据应该活过切换吗?该活 → 会话;不该 → 实例」</b>——
 * 分组/职能/生命周期阶段等玩法语义字段不该活过切换,不在会话名册上,
 * 那些状态归实例侧组件所有(参赛者随实例 close 全灭)。
 *
 * <p>向本 record 加任何字段前先过上面的自问句;向它加实例侧字段即两层成员混淆,review 打回。
 */
public record SessionMember(UUID uuid, ConnectionStatus connection, long joinedAtMillis) {

    public SessionMember {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(connection, "connection");
    }

    /** 连接态:在册成员的断线/重连标记(宽限期内成员保留、重连即恢复,§3.1)。封闭枚举,新增值须过设计评审。 */
    public enum ConnectionStatus {
        /** 在线。 */
        ONLINE,
        /** 已断线:仍在册,等待宽限期内重连或宽限期满由清点移除。 */
        DISCONNECTED
    }
}
