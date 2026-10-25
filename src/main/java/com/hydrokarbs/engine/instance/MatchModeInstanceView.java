package com.hydrokarbs.engine.instance;

import java.util.Optional;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.port.SessionView;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.map.MapDefinition;

/**
 * 实例只读读口:内容侧(判定器等)消费实例数据的最小面(施工计划 1.3)。
 *
 * <p>本阶段由实例容器的内部实现体承载,经 {@code MatchModeInstance#view()} 暴露;
 * 方法面随消费方只增不改。{@code component(Class)} 未命中返回 null
 * (与组件查找端口同口径)。
 *
 * <p>★v4(2.6)签名修订(计划内接线,记 deviations;阶段1 已知问题 4 的消除路径):
 * {@code session()} 返回类型由裸 {@code Session} 改为只读视图
 * {@link SessionView}——与 {@code MatchModeInstancePort#session()} 同型
 * (容器 self 端口组以同一方法双满足两接口);component/phase、endgate、rulebook
 * 三件私有适配器的 {@code session()} 由「暂返 null」改为经服务集只读口真接通。
 */
public interface MatchModeInstanceView {

    /** 按类型取第一个可赋值组件;未注册返回 null(不抛)。 */
    <T extends MatchModeComponent> T component(Class<T> type);

    /** 读实例属性袋:键不存在返回 empty。 */
    <T> Optional<T> attr(AttributeBag.Key<T> key);

    /** 本次运行的模式参数(只读配置视图)。 */
    MatchModeConfig cfg();

    /** 本次运行的地图。 */
    MapDefinition map();

    /** 实例已存活 tick 数。 */
    long tick();

    /** 所属会话的只读视图(★v4-D5:跨级只读;原裸 Session 引用,2.6 收窄)。 */
    SessionView session();
}
