package com.hydrokarbs.api.view;

import java.util.Set;

/**
 * 地图快照:api 侧不可变 record(07-公共API §7.1/§7.2 值对象行;7.1 施工文档步骤 4)。
 *
 * <p>数据源 = {@code map/MapDefinition}(§15.2 地图文件;纯静态值,20-D1)。
 * <b>分量与施工文档八分量形状的出入(按实际字段落,7.1 步骤 4 的适配口径)</b>:
 * <ul>
 *   <li><b>不设 {@code supportedModes}</b>:{@code MapDefinition} 无该字段——
 *       「{@code supported-modes} 显式收窄」在 §15.2 未实装(MapRegistry/forMap 处
 *       TODO 注:后续阶段评估);模式↔地图的耦合面是 {@code mapTags × tags} 交集,
 *       查询期计算(§12.2 forMap),快照不预演;</li>
 *   <li><b>不设 {@code minPlayers}/{@code maxPlayers}</b>:§15.2 框架地图键表无此
 *       二键——{@code MapDefinition.metadata} 透传不解释(MapDefinition javadoc),
 *       {@code metadata.min-players} 约定只住 Shdf 玩法线文档,框架不解释内容词汇;</li>
 *   <li>{@code resetStrategy} 存 {@code name()} 字符串(枚举不进冻结面,
 *       §0.5.3 末行同款理由)。</li>
 * </ul>
 */
public record MapInfo(String id, String displayName, String world, Set<String> tags,
                      String resetStrategy) {

    public MapInfo {
        tags = Set.copyOf(tags);
    }
}
