package com.hydrokarbs.engine.session;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 能力标签注册表——<b>最小占位壳</b>(施工计划 1.6「交付次序注」)。
 *
 * <p>本壳只提供字符串标签的注册/摘除/查询三口,支撑进程级服务装配在
 * seal 期注册能力标签、shutdown 摘除(08-角色桥接 §8.8 语义扩注:
 * 注册者 = L1 / 第三方插件 / 服务(seal 期);机制与字符串标签口径不变,
 * 词汇由提供方定义,引擎零字面量)。
 *
 * <p><b>正式本体归阶段 2(2.3)接管</b>(12-代码骨架 §12.1 所有权边界):
 * 模式声明的 {@code requiredCapabilities} 并集消费与 A2 装配校验、
 * 软依赖动态刷新(14.22)均不在本壳;本壳与 1.3 的 {@code engine/session}
 * 编译壳同款口径——先立最小落点支撑编译与离线断言,正式注册路径由 2.3 接管。
 * (待裁决①已登记于 1.6 文档「关键实现要点」末条,无静默决定。)
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

    private static String requireValid(String tag) {
        Objects.requireNonNull(tag, "tag");
        if (tag.isBlank()) {
            throw new IllegalArgumentException("能力标签不可为空白串");
        }
        return tag;
    }
}
