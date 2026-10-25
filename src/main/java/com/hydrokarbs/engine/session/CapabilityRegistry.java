package com.hydrokarbs.engine.session;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 能力标签注册表:字符串标签机制件(施工计划 1.6 立最小壳、2.3 接管扩用;
 * 08-角色桥接 §8.8 语义扩注:注册者 = L1 / 第三方插件 / 服务(seal 期);
 * 机制与字符串标签口径不变,词汇由提供方定义,引擎零字面量)。
 *
 * <p><b>2.3 接管后的消费面</b>:切换准备阶段 A2 的能力校验——目标模式声明的
 * {@code requiredCapabilities} 必须 ⊆ {@link #available()},缺失即拒绝
 * (03-会话与模式切换 §3.2-A2);进程级服务装配在 seal 期注册标签、
 * shutdown 摘除(既有)。<b>软依赖动态刷新(14.22 的机制同源)留 3.3,注释预留</b>
 * (监听宿主插件装/卸事件刷新可用标签,词汇仍由提供方定义)。
 *
 * <p><b>注册形态保持 tag-only</b>(用户裁决口径):{@code register(String, Plugin)}
 * 带提供方形态不引入——既有调用(服务装配 seal 期)与离线测试零迁移;
 * 提供方归属的追溯诉求出现时再走设计评审扩口,不静默决定。
 *
 * <p>单线程约定:全部方法主线程({@code onEnable}/{@code onDisable} 天然满足);
 * 内部普通集合,不加锁(18 号修正案 D7)。
 */
public final class CapabilityRegistry {

    private final Set<String> tags = new LinkedHashSet<>();

    /** 注册一个能力标签(幂等;重复注册同一标签无副作用)。 */
    public void register(String tag) {
        tags.add(requireValid(tag));
    }

    /** 摘除一个能力标签(幂等;标签缺席时静默返回)。 */
    public void unregister(String tag) {
        tags.remove(requireValid(tag));
    }

    /** 当前全部已注册标签(只读快照;诊断与装配校验消费面)。 */
    public Set<String> available() {
        return Set.copyOf(tags);
    }

    /** 单标签校验(A2 装配校验的逐项口):已注册返回 true。 */
    public boolean isAvailable(String tag) {
        return tags.contains(requireValid(tag));
    }

    private static String requireValid(String tag) {
        Objects.requireNonNull(tag, "tag");
        if (tag.isBlank()) {
            throw new IllegalArgumentException("能力标签不可为空白串");
        }
        return tag;
    }
}
