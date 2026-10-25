package com.hydrokarbs.engine.session;

import java.util.List;
import java.util.Objects;

import com.hydrokarbs.engine.component.SessionComponent;

/**
 * 会话声明:初始模式、地图策略与会话级组件表(03-会话与模式切换 §3.1「spec 含初始
 * 模式与地图策略」;★v4-D4 增 {@code sessionComponents};2.1 施工步骤 2、2.6 步骤 6)。
 *
 * <p>纯数据,由<b>宿主构造</b>。{@code initialModeId} 是字符串——Session 自身
 * 不解析它(解析是切换 A1 对 {@code MatchModeRegistry} 的查询);它是每会话首切换
 * 目标,空串 = 未配置(合法待机状态);引擎不认识任何具体模式 id。
 * {@code mapPool} 亦由宿主给。
 *
 * <p><b>★v4-D4 会话组件表</b>:有序表——<b>有序 = 装配序 = 派发序</b>
 * (声明序纪律);由<b>宿主组装时构建</b>(挂不挂组件、参数给多少,全是宿主的声明),
 * <b>模式无权注册/增删会话级组件</b>(第一版)。传入即冻结:构造做防御性拷贝,暴露不可变视图
 * (Spec 自身的可变声明经装配期 {@code freeze()} 收口,共享 SPEC 常量跨会话互不串改)。
 */
public record SessionSpec(SessionId id, String initialModeId, List<String> mapPool,
                          List<SessionComponent.Specification<?>> sessionComponents) {

    /** 三参便捷构造(无会话组件;既有调用零迁移:rotation 拓扑/测试桩同此口径)。 */
    public SessionSpec(SessionId id, String initialModeId, List<String> mapPool) {
        this(id, initialModeId, mapPool, List.of());
    }

    public SessionSpec {
        Objects.requireNonNull(id, "id");
        // initialModeId 允许空串——空 = 初始模式未配置(合法待机状态:建会话记 WARN、
        // 会话保留待后补切换);仅 null 拒。引擎对空串照走切换 A1 未注册拒绝,零特判。
        Objects.requireNonNull(initialModeId, "initialModeId");
        Objects.requireNonNull(sessionComponents, "sessionComponents");
        mapPool = List.copyOf(mapPool);
        sessionComponents = List.copyOf(sessionComponents);
    }
}
