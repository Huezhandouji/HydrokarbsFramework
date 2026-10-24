package com.hydrokarbs.engine.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import com.hydrokarbs.engine.component.ComponentDependencyException;
import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.WorldHandle;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.map.MapDefinition;

/**
 * 模式聚合根:模式的纯数据聚合 + 唯一有序组件描述符表(02-核心概念 §2.3)。
 *
 * <p>★ 全类零行为分支:只有信息与组件声明。仅有的两个方法都是装配期职责
 * ({@link #verifyDependencies} 与 {@link #createInstance})。任何「顺手的方法」
 * 都意味着有一段行为找不到组件之家——正确的动作永远是给某个组件的装配描述符
 * 加构造数据,或新造一个组件(§2.8-决断二;★22 冻结公开方法集恰为 9 个)。
 *
 * <p>新增一个模式 = 用 Builder 拼一张组件表;不存在「实现 MatchMode 接口」这回事。
 * 模板可以是进程级单例:模板不变,实例万变。build 后不可追加组件(A9)。
 */
public final class MatchMode {

    private final String id;
    private final Component displayName;
    private final List<Component> description;
    private final Material icon;                 // 入口牌子/GUI 用;未设置时为 null(消费方自理)
    private final Set<String> mapTags;           // 支持哪些地图(按 MapDefinition.tags 匹配)
    private final MatchModeMeta meta;            // 能力标签/是否记史/人数窗/缺省去向(入场裁决已归组件)

    /** 唯一有序组件描述符表:LinkedHashMap,声明序 = 装配调用序 = 生命周期/事件派发序。 */
    private final Map<String, ComponentEntry> components;

    private MatchMode(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.icon = builder.icon;
        this.mapTags = builder.mapTags;
        this.meta = builder.meta;
        this.components = Collections.unmodifiableMap(new LinkedHashMap<>(builder.components));
    }

    // ── 仅有的两个方法(都是装配期职责) ─────────────────────────────

    /**
     * 装配期依赖检查:缺必需依赖抛 {@link ComponentDependencyException}(算法逐行照 §2.6,见下)。
     * 注册前闸与切换准备阶段调用;在这里失败,旧实例毫发无损。
     *
     * <p>算法(§2.6):A 的必需类型 R 被满足 ⟺ 存在另一组件 B(id ≠ A)使
     * {@code R.isAssignableFrom(B.providedType())};自己不算自己的提供者(自环仍算缺依赖、硬失败);
     * 可选依赖缺失不报错;环不检查(允许环形依赖,唤醒顺序 = 声明序,环内先后未定义——不得依赖)。
     * 检查只看描述符声明的类型,不需要实例。
     */
    public void verifyDependencies() {
        for (Map.Entry<String, ComponentEntry> entry : components.entrySet()) {
            List<String> missing = missingRequiredDependencies(entry.getKey());
            if (!missing.isEmpty()) {
                throw new ComponentDependencyException(
                        "模式 " + id + " 的组件 " + entry.getKey() + " 缺少必需依赖: " + String.join(", ", missing));
            }
        }
    }

    /**
     * 造实例的唯一入口:先 {@link #verifyDependencies} 再造——任何路径不可能绕过检查。
     * 容器(1.3)按描述符表造组件并构造期注入服务集。
     */
    public MatchModeInstance createInstance(Session session, MapDefinition map, MatchModeConfig cfg,
                                            WorldHandle world, Handoff handoff, EngineServices engine) {
        verifyDependencies();
        return new MatchModeInstance(this, session, map, cfg, world, handoff, engine);
    }

    // ── 只读访问器(短名,非 JavaBean 的 getXxx;★22 方法集按这些名字冻结) ──

    public String id() {
        return id;
    }

    public Component displayName() {
        return displayName;
    }

    public List<Component> description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    public Set<String> mapTags() {
        return mapTags;
    }

    public MatchModeMeta meta() {
        return meta;
    }

    public Map<String, ComponentEntry> components() {
        return components;
    }

    // ── 诊断(★22 收口:一律 private,公开面不进诊断方法) ─────────────

    /** 指定组件缺失的必需依赖类型名(可选项缺失不计;自己不算自己的提供者)。 */
    private List<String> missingRequiredDependencies(String componentId) {
        ComponentEntry self = components.get(componentId);
        List<String> missing = new ArrayList<>();
        for (Class<? extends MatchModeComponent> required : self.requiredTypes()) {
            if (!hasProviderOtherThan(componentId, required)) {
                missing.add(required.getName());
            }
        }
        return missing;
    }

    /** 是否存在 id ≠ selfId、提供类型可赋给 type 的组件(自己不算自己的提供者)。 */
    private boolean hasProviderOtherThan(String selfId, Class<? extends MatchModeComponent> type) {
        for (Map.Entry<String, ComponentEntry> other : components.entrySet()) {
            if (other.getKey().equals(selfId)) {
                continue;
            }
            if (type.isAssignableFrom(other.getValue().providedType())) {
                return true;
            }
        }
        return false;
    }

    /** 装配条目:(工厂, 描述符类型, 提供类型, 必需依赖, 可选依赖) 的不可变快照。不持有描述符对象。 */
    public record ComponentEntry(
            MatchModeComponent.Factory<? extends MatchModeComponent> factory,
            Class<?> descriptorType,
            Class<? extends MatchModeComponent> providedType,
            List<Class<? extends MatchModeComponent>> requiredTypes,
            List<Class<? extends MatchModeComponent>> optionalTypes) {
    }

    /** 聚合根 Builder:唯一装配入口(声明序 = 入表序)。 */
    public static final class Builder {

        private final String id;
        private Component displayName;
        private List<Component> description;
        private Material icon;
        private Set<String> mapTags = Set.of();
        private MatchModeMeta meta;
        private final Map<String, ComponentEntry> components = new LinkedHashMap<>();

        public Builder(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("MatchMode id cannot be null or empty.");
            }
            this.id = id;
        }

        public Builder displayName(Component displayName) {
            this.displayName = java.util.Objects.requireNonNull(displayName, "displayName");
            return this;
        }

        public Builder description(List<Component> lines) {
            this.description = List.copyOf(java.util.Objects.requireNonNull(lines, "description"));
            return this;
        }

        public Builder icon(Material icon) {
            this.icon = java.util.Objects.requireNonNull(icon, "icon");
            return this;
        }

        public Builder mapTags(Set<String> tags) {
            this.mapTags = Set.copyOf(java.util.Objects.requireNonNull(tags, "mapTags"));
            return this;
        }

        public Builder meta(MatchModeMeta meta) {
            this.meta = java.util.Objects.requireNonNull(meta, "meta");
            return this;
        }

        /**
         * 统一装配入口:绑 id → {@code freeze()} 取快照 → 入表。
         * id 跨类型查重(同一命名空间,重复即抛 {@link IllegalArgumentException})。
         */
        public Builder addComponent(String id, MatchModeComponent.Specification<?> spec) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("Component id cannot be null or empty.");
            }
            java.util.Objects.requireNonNull(spec, "spec");
            if (components.containsKey(id)) {
                throw new IllegalArgumentException("重复的组件 id: " + id + "(模式 " + this.id + ")");
            }
            spec.bindId(id);
            MatchModeComponent.Specification.Snapshot snapshot = spec.freeze();
            components.put(id, new ComponentEntry(
                    snapshot.factory(),
                    spec.getClass(),
                    snapshot.providedType(),
                    snapshot.requiredTypes(),
                    snapshot.optionalTypes()));
            return this;
        }

        /** 产出不可变聚合根。兜底:displayName 缺省 = id;description 缺省 = 占位文案;meta 必须显式设置。 */
        public MatchMode build() {
            if (meta == null) {
                throw new IllegalArgumentException("模式 " + id + " 未设置 meta(元数据无预设,必须逐字段自建)");
            }
            if (displayName == null) {
                displayName = Component.text(id);
            }
            if (description == null) {
                description = List.of(Component.text("(暂无描述)"));
            }
            return new MatchMode(this);
        }
    }
}
