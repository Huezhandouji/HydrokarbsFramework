package com.hydrokarbs.engine.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.hydrokarbs.engine.instance.Participant;

/**
 * 会话名册:成员的不可变快照(03-会话与模式切换 §3.1)。
 *
 * <p>名册是玩家进服/退服/被踢的唯一登记处;本类只承载快照与「原地位移」:
 * 同一批 uuid 在实例启动时整体成为参赛者(位移语义,§3.2-B4——
 * 全体成员就地成为参赛者,初始标记 JOINING)。名册的增删口归阶段 2 的会话实现。
 */
public final class SessionRoster {

    private final List<SessionMember> members;

    public SessionRoster(List<SessionMember> members) {
        Objects.requireNonNull(members, "members");
        this.members = List.copyOf(members);
    }

    /** 成员快照(不可变;迭代序 = 传入序)。 */
    public List<SessionMember> members() {
        return members;
    }

    public int size() {
        return members.size();
    }

    /**
     * 名册原地位移的实例侧落点:同一批 uuid 变参赛者(初始标记 JOINING,§3.2-B4)。
     * 返回的映射供实例容器整体并入参赛者表。
     */
    public Map<UUID, Participant> toParticipants() {
        Map<UUID, Participant> result = new LinkedHashMap<>();
        for (SessionMember member : members) {
            result.put(member.uuid(), new Participant(member.uuid()));
        }
        return result;
    }
}
