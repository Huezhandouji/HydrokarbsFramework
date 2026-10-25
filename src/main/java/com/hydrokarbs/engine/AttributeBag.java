package com.hydrokarbs.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.hydrokarbs.engine.component.MatchModeComponent;

/**
 * 按命名空间隔离的键值袋(02-核心概念 §2.7)。
 *
 * <p><b>加严①</b>:实例 attrs 与参赛者 attrs 随实例 {@code close()} 整体销毁,
 * {@code Handoff} 是实例数据唯一合法「带出去」通道(容器在 1.3 落实;
 * 跨切换存活的会话级状态寄居会话级组件,随解散全灭)。
 *
 * <p><b>加严②</b>:命名空间 = 组件 id——组件只能在自己名下写。
 * {@link #set} 带开发期断言(默认开、生产可经 {@link #setNamespaceCheckEnabled} 关):
 * 用 {@code StackWalker} 找调用栈上最近的 {@link MatchModeComponent} 子类帧,
 * 断言键的 namespace 即该组件的 id。跨组件共享走状态主人的强类型读口,
 * 不开放裸写(「状态唯一」的推论)。
 *
 * <p>找不到组件帧的调用(容器/会话壳,如写 {@code session} 命名空间的内核属性)
 * 只允许经包内 {@code setUnchecked}——公开 {@code set} 对无组件帧的调用直接拒绝,
 * 这是「session 命名空间只有内核可写」的机械收口。
 */
public final class AttributeBag {

    /** 属性键三元组:命名空间(组件 id)+ 名字 + 值类型。 */
    public record Key<T>(String namespace, String name, Class<T> type) {

        public static <T> Key<T> of(String namespace, String name, Class<T> type) {
            return new Key<>(namespace, name, type);
        }
    }

    /** 开发期命名空间断言开关:默认开,生产可关(断言只为开发期执法,不承担运行期正确性)。 */
    private static volatile boolean namespaceCheckEnabled = true;

    private final Map<Key<?>, Object> values = new HashMap<>();

    /** 授权视图:组件 id → 组件类(容器装配完组件后灌入;供栈帧反查「调用组件 id」)。 */
    private final Map<String, Class<?>> componentTypes = new HashMap<>();

    public static void setNamespaceCheckEnabled(boolean enabled) {
        namespaceCheckEnabled = enabled;
    }

    public static boolean isNamespaceCheckEnabled() {
        return namespaceCheckEnabled;
    }

    /**
     * 写入(仅组件可经本口):开发期断言命名空间 = 调用组件自身的 id。
     * 断言失败抛 {@link IllegalStateException},消息点名:调用组件 id、键的 namespace、键名。
     */
    public <T> void set(Key<T> key, T value) {
        if (namespaceCheckEnabled) {
            Class<?> frame = nearestComponentFrame();
            if (frame == null) {
                throw new IllegalStateException(
                        "AttributeBag#set 只允许组件调用(栈上无组件帧);框架/会话壳请经包内 setUnchecked 写入");
            }
            Class<?> declared = key.namespace() != null ? componentTypes.get(key.namespace()) : null;
            if (declared != frame) {
                throw new IllegalStateException("命名空间断言失败:调用组件 id=" + componentIdOf(frame)
                        + ",键 namespace=" + key.namespace() + ",键名=" + key.name());
            }
        }
        values.put(key, value);
    }

    /** 取值:键不存在返回 empty。 */
    public <T> Optional<T> get(Key<T> key) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(key.type().cast(value));
    }

    /** 取值,键不存在返回 fallback。 */
    public <T> T getOrDefault(Key<T> key, T fallback) {
        return get(key).orElse(fallback);
    }

    /** 是否存在该键(不论取值类型)。 */
    public boolean has(Key<?> key) {
        return values.containsKey(key);
    }

    // ── 包内收口(容器/会话壳专用) ────────────────────────────────

    /** 包内写口:无组件帧的调用方(容器/会话壳,写 {@code session} 等内核命名空间)唯一合法通道。 */
    void setUnchecked(Key<?> key, Object value) {
        values.put(key, value);
    }

    // ── 装配期登记口(公开面只对容器) ─────────────────────────────

    /**
     * 公开登记口:仅限实例/会话容器在装配期调用——组件装配完成后灌入
     * (组件 id → 组件类)授权表,供 {@link #set} 的命名空间断言反查;
     * 业务代码禁止调用。
     */
    public void bindComponent(String componentId, Class<?> componentType) {
        componentTypes.put(componentId, componentType);
    }

    // ── 内部:栈帧定位与反查 ─────────────────────────────────────

    /** 调用栈上最近的组件子类声明类;无组件帧返回 null。 */
    private static Class<?> nearestComponentFrame() {
        return StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE))
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(owner -> owner != AttributeBag.class
                                && MatchModeComponent.class.isAssignableFrom(owner))
                        .findFirst()
                        .orElse(null));
    }

    /** 反查组件类对应的已登记 id(诊断文案用;同类多实例罕见边界下取第一个匹配)。 */
    private String componentIdOf(Class<?> frame) {
        String found = componentTypes.entrySet().stream()
                .filter(entry -> entry.getValue() == frame)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("/"));
        return found.isEmpty() ? frame.getName() + "(未登记)" : found;
    }
}
