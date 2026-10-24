package com.hydrokarbs.engine.instance;

import java.util.Optional;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.map.MapDefinition;

/**
 * 实例只读读口:内容侧(判定器等)消费实例数据的最小面(施工计划 1.3)。
 *
 * <p>本阶段由实例容器的内部实现体承载,经 {@code MatchModeInstance#view()} 暴露;
 * 方法面随消费方只增不改。{@code component(Class)} 未命中返回 null
 * (与组件查找端口同口径)。
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

    /** 所属会话(只读引用;会话骨架的完整读面归阶段 2)。 */
    Session session();
}
