package com.hydrokarbs.engine.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 模式间交接的类型化键值袋(03-会话与模式切换 §3.3)。
 *
 * <p>与 {@code AttributeBag} 同一套键值模式(命名空间 + 名字 + 类型),
 * 杜绝「用 {@code Map<String,Object>} 传交接数据、接收端强转炸裂」的写法。
 *
 * <p>命名空间 = 写入方自己的 id(模式 id / 组件 id / 第三方插件 id);
 * 框架不写任何键、不保留任何命名空间。
 * 键随主人:谁生产谁持有公开常量,跨组件引用交接键常量是合法协作——
 * 交接本就是跨模式通道(与 {@code AttributeBag} 的「不得写他人命名空间」是两回事)。
 *
 * <p>{@link #get} 类型不符返回 empty 不抛(§14.20-②,防类型漂移);
 * {@link #merge} 外部同名键覆盖旧实例自报(外部指令优先,§3.2-B3)。
 */
public final class Handoff implements HandoffView {

    /** 交接键三元组:命名空间(写入方 id)+ 名字 + 值类型。 */
    public record Key<T>(String namespace, String name, Class<T> type) {

        public static <T> Key<T> of(String namespace, String name, Class<T> type) {
            return new Key<>(namespace, name, type);
        }
    }

    private final Map<Key<?>, Object> values;

    private Handoff(Map<Key<?>, Object> values) {
        this.values = values;
    }

    /** 空交接(可继续写入的独立实例,不共享单例)。 */
    public static Handoff empty() {
        return new Handoff(new LinkedHashMap<>());
    }

    /** 单键工厂:便捷构造只携带一键的交接载荷(用法示例:结束判定把投票结果交出去)。 */
    public static <T> Handoff of(Key<T> key, T value) {
        Handoff handoff = empty();
        handoff.put(key, value);
        return handoff;
    }

    /** 合并:external 的同名键覆盖 outgoing(外部指令优先于旧实例自报,§3.2-B3)。 */
    public static Handoff merge(Handoff outgoing, Handoff external) {
        Map<Key<?>, Object> merged = new LinkedHashMap<>();
        if (outgoing != null) {
            merged.putAll(outgoing.values);
        }
        if (external != null) {
            merged.putAll(external.values);
        }
        return new Handoff(merged);
    }

    /** 写入(写侧;入口侧经 {@link HandoffView} 只读)。 */
    public <T> void put(Key<T> key, T value) {
        values.put(java.util.Objects.requireNonNull(key, "key"), value);
    }

    @Override
    public <T> Optional<T> get(Key<T> key) {
        Object value = values.get(key);
        if (value == null || !key.type().isInstance(value)) {
            return Optional.empty();    // 键不存在或类型漂移:empty 不抛(§14.20-②)
        }
        return Optional.of(key.type().cast(value));
    }

    /**
     * 是否存在该键:<b>不论取值类型</b>(同「命名空间 + 名字」即算存在)——
     * 与 {@link #get} 的「类型不符返回 empty」配套:类型漂移探测先用 has 确认键在,
     * 再用 get 拿不到值即知类型漂移(§14.20-②)。
     */
    @Override
    public boolean has(Key<?> key) {
        if (key == null) {
            return false;
        }
        for (Key<?> existing : values.keySet()) {
            if (existing.namespace().equals(key.namespace()) && existing.name().equals(key.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 交接写侧:切换阶段 B1 逐组件 {@code onHandoff(Builder out)} 钩子的写入对象
     * (02-核心概念 §2.4)。容器收拢各组件写入后 {@link #build()} 得到交接载荷。
     */
    public static final class Builder {

        private final Map<Key<?>, Object> values = new LinkedHashMap<>();

        /** 写入一键。 */
        public <T> Builder put(Key<T> key, T value) {
            values.put(java.util.Objects.requireNonNull(key, "key"), value);
            return this;
        }

        /** 收拢成不可再由本构造器改动的交接载荷(值表快照拷贝)。 */
        public Handoff build() {
            return new Handoff(new LinkedHashMap<>(values));
        }
    }
}
