package com.hydrokarbs.engine.session;

import java.util.Objects;
import java.util.UUID;

/**
 * 会话级成员:uuid、连接态、加入时间(03-会话与模式切换 §3.1 职责清单「名册」行)。
 *
 * <p>★ 刻意只有三件:玩法语义字段(分组/职能/生命周期阶段等)是实例概念,
 * 不在会话名册上——那些状态归实例组件所有(14-陷阱清单 §14.18「两层成员」)。
 */
public record SessionMember(UUID uuid, boolean online, long joinedAtMillis) {

    public SessionMember {
        Objects.requireNonNull(uuid, "uuid");
    }
}
