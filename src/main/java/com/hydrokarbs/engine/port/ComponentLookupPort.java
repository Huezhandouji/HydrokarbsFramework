package com.hydrokarbs.engine.port;

import java.util.List;
import java.util.Optional;

import com.hydrokarbs.engine.component.MatchModeComponent;

/**
 * 实例内组件查找端口:按类型 / 按 id,含 getAll(02-核心概念 §2.5)。
 *
 * <p>组件协作的唯一取物口(「基类不认识子类」纪律的另一半:要协作就按类型取)。
 * 实现体是 1.3 的实例组件注册表(声明序快照遍历)。
 */
public interface ComponentLookupPort {

    /** 按类型取第一个可赋值组件;未注册返回 null(不抛)。 */
    <T extends MatchModeComponent> T get(Class<T> type);

    /** 按类型取全部可赋值组件(声明序);无符合者返回空列表。 */
    <T> List<T> getAll(Class<T> type);

    /** 按 id 取组件(装配时绑入的 id);不存在返回 empty。 */
    Optional<MatchModeComponent> byId(String id);
}
