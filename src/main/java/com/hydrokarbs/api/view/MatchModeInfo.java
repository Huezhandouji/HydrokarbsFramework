package com.hydrokarbs.api.view;

import java.util.List;
import java.util.Set;

import org.bukkit.Material;

/**
 * 模式快照:api 侧不可变 record(07-公共API §7.1/§7.2 值对象行;7.1 施工文档步骤 3)。
 *
 * <p>字段映射(数据源 = {@code MatchMode}/{@code MatchModeMeta},§2.3/§2.5):
 * <ul>
 *   <li>{@code displayName}/{@code description}:Adventure {@code Component} →
 *       MiniMessage <b>序列化串</b>(消费者可再解析;§14.10 纪律随消费方——玩家可控串
 *       不得直接喂 MiniMessage,序列化发生在框架侧对模式声明文案的转换点);</li>
 *   <li>{@code icon}:Bukkit {@link Material} 原样;未声明时为 {@code null}
 *       ({@code MatchMode#icon()} 既有口径:消费方自理);</li>
 *   <li>{@code defaultTransition}:枚举存 {@code name()} 字符串——冻结面不被引擎枚举
 *       演进绑死(拒绝码收缩先例,§0.5.3 末行)。</li>
 * </ul>
 *
 * <p><b>第三方可见性(7.2)</b>:第三方插件经 {@code MatchModeProvider} 注册
 * (ServicesManager 收录,§7.2/§13.4)入目录后,服主经 {@code MiniGameApi#modes()}
 * 即见本快照——与其他模式同列、无特判(14.21);id 词汇由提供方定义,
 * 引擎零具体模式词汇。
 *
 * <p>快照纪律:集合构造期 {@code copyOf};不含组件描述符表(那是引擎装配面,
 * A9 三限制:build 后不可变,07 §7.2)。
 */
public record MatchModeInfo(String id, String displayName, List<String> description,
                            Material icon, Set<String> mapTags, boolean recordsHistory,
                            int minParticipants, int maxParticipants, String defaultTransition) {

    public MatchModeInfo {
        description = List.copyOf(description);
        mapTags = Set.copyOf(mapTags);
    }
}
