package com.hydrokarbs.api.view;

import java.util.Set;

/**
 * 地图快照:api 侧不可变 record(07-公共API §7.1/§7.2 值对象行;7.1 施工文档步骤 4)。
 *
 * <p>数据源 = {@code map/MapDefinition}(纯静态值,20-D1)。★v1.8 视图收缩
 * (26 号修正案议题:MapDefinition 瘦身,快照只留框架有消费面的字段):
 * {@code id} 为声明目录名(路径派生);{@code tags} 取声明
 * {@code MapDefinition#tags()} 唯一读口(缺省空集);{@code resetStrategy} 取声明
 * 字段的值存 {@code name()}(枚举不进冻结面,§0.5.3 末行同款理由)。原
 * {@code displayName}/{@code world} 分量随瘦身删除(无对应来源;声明文件系统
 * 路径不进冻结面)。
 */
public record MapInfo(String id, Set<String> tags, String resetStrategy) {

    public MapInfo {
        tags = Set.copyOf(tags);
    }
}
