package com.hydrokarbs.engine.component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.port.SessionComponentLookupPort;
import com.hydrokarbs.engine.port.SessionServices;
import com.hydrokarbs.engine.session.SessionId;

/**
 * 会话组件注册表:会话版 {@link ComponentRegistry}(★v4;02-核心概念 §2.5.1;
 * 03-会话与模式切换 §3.8;施工计划 2.6 步骤 5)。
 *
 * <p><b>每会话一份的机械保证</b>(★27):注册表挂在 {@code Session} 上
 * (<b>不是进程级静态</b>)——arena 拓扑下 N 个会话并发跑同一模板互不串台;
 * 共享的只能是不可变声明(Spec/Snapshot/SPEC 常量),有状态实例永不跨会话共享。
 * 注册表<b>不持有组件状态,只持描述符与实例引用</b>(§2.8-决断三扩展)。
 *
 * <p><b>职责清单</b>:
 * <ul>
 *   <li>{@link #bind}:装配期登记快照(id 去重,id 跨类型同一命名空间);</li>
 *   <li>{@link #verifyDependencies()}:装配期依赖校验(算法逐行照 02 §2.6 六行,
 *       类型换成会话级:自己不算自己的提供者、环不检查、自环算缺、
 *       缺必需依赖抛 {@link ComponentDependencyException} 点名);</li>
 *   <li>{@link #createAll} / {@link #awakeAll} / {@link #startAll}:按<b>声明序</b>
 *       (装配序 = 声明序 = 派发序,14.30);装配期抛异常上抛 →
 *       <b>会话创建失败</b>(响亮,L0 SEVERE;与运行期隔离语义不可混淆);</li>
 *   <li>{@link #tickAll} / {@link #dispatchEvent}:逐组件 try/catch → D9 隔离
 *       (记日志 → safeStop + 打标跳过其派发 → 发 {@code SessionComponentQuarantined}
 *       → <b>会话不解散</b>,其余组件照常);</li>
 *   <li>{@link #stopAll}:<b>逆序</b> stop(逐个 safeStop,异常不中断;已隔离组件
 *       已停用,不重复停)——<b>stop 由框架无条件调用,无单例豁免条款</b>;</li>
 *   <li>{@link #reclaimRegisteredResources()}:登记资源(订阅/延时任务/显示)幂等统一回收
 *       (与实例级同法,§1.3 步 4);</li>
 *   <li>查找口({@link #get}/{@link #getAll}/{@link #byId})实现
 *       {@link SessionComponentLookupPort},即 svc 服务集的 components 成员。</li>
 * </ul>
 *
 * <p>★ 待裁决登记(⑤,D3 落盘注):D3 原文「bind 幂等」与「同会话重复绑定同 id 抛
 * {@code IllegalStateException}」语义相抵——本版对照 1.3 实例级 {@link ComponentRegistry}
 * 的「组件重复注册(编程错误)抛」口径,<b>落地为重复即抛</b>;候选
 * 「同 id 同快照幂等去重、异快照抛」待上游裁决,未静默决定。
 */
public final class SessionComponentRegistry implements SessionComponentLookupPort {

    private static final Logger LOG = Logger.getLogger(SessionComponentRegistry.class.getName());

    private final SessionId sessionId;
    private final SessionEventBus bus;

    /** 声明序登记表:id → 绑定项(快照 + 实例引用;实例在 createAll 填入)。 */
    private final Map<String, Binding> bindingsById = new LinkedHashMap<>();

    /** 每组件资源表(订阅注销/延时任务取消/显示移除的句柄统一挂这里)。 */
    private final Map<SessionComponent, List<AutoCloseable>> resourcesByComponent =
            new IdentityHashMap<>();

    /** 发起方不在组件栈上的登记桶(事件桥接等框架自有句柄;回收同样覆盖)。 */
    private final List<AutoCloseable> unownedResources = new ArrayList<>();

    /** 已隔离组件(打标后跳过其 update/onEvent 派发与 stopAll 的重复停用;D9)。 */
    private final Set<SessionComponent> quarantined = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 绑定项:装配快照 + 组件实例引用(注册表不持有组件状态,只持描述符与实例)。 */
    private record Binding(SessionComponent.Specification.Snapshot snapshot, SessionComponent instance) {
    }

    public SessionComponentRegistry(SessionId sessionId, SessionEventBus bus) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    // ── 装配期(会话创建内;异常上抛 = 会话创建失败,响亮) ──────────────

    /**
     * 登记快照(id 跨类型去重——同一命名空间)。★ 待裁决⑤:同会话重复绑定同 id 抛
     * {@link IllegalStateException}(对照实例级「重复注册即抛」口径);
     * 「同 id 同快照幂等去重」候选待上游裁决。
     */
    public void bind(String id, SessionComponent.Specification.Snapshot snapshot) {
        Objects.requireNonNull(id, "id");
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("Component id cannot be null or empty.");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        if (bindingsById.containsKey(id)) {
            throw new IllegalStateException("会话组件重复绑定同 id(编程错误): " + id
                    + "(会话 " + sessionId.value() + ")");
        }
        bindingsById.put(id, new Binding(snapshot, null));
    }

    /**
     * 装配期依赖校验(算法逐行照 02 §2.6 六行,类型换会话级):
     * A 的必需类型 R 被满足 ⟺ 存在另一绑定项 B(id ≠ A)使
     * {@code R.isAssignableFrom(B.providedType())};<b>自己不算自己的提供者</b>
     * (自环仍算缺,硬失败);可选依赖缺失不报错;<b>环不检查</b>(允许环形依赖,
     * awake 顺序 = 声明序,环内「谁先醒」未定义——不得依赖)。
     * 缺必需依赖抛 {@link ComponentDependencyException} 点名(会话 id/组件 id/缺的类型)。
     */
    public void verifyDependencies() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Binding> entry : bindingsById.entrySet()) {
            SessionComponent.Specification.Snapshot snapshot = entry.getValue().snapshot();
            for (Class<? extends SessionComponent> required : snapshot.requiredTypes()) {
                if (!isProvidedByOther(entry.getKey(), required)) {
                    problems.add("会话 " + sessionId.value() + " 组件 " + entry.getKey() + "("
                            + snapshot.descriptorLabel() + ") 缺必需依赖: " + required.getName());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ComponentDependencyException(String.join("; ", problems));
        }
    }

    /** R 是否被「另一个 id」的绑定项供给(自己不算自己的提供者,§2.6)。 */
    private boolean isProvidedByOther(String selfId, Class<? extends SessionComponent> required) {
        for (Map.Entry<String, Binding> entry : bindingsById.entrySet()) {
            if (entry.getKey().equals(selfId)) {
                continue;
            }
            if (required.isAssignableFrom(entry.getValue().snapshot().providedType())) {
                return true;
            }
        }
        return false;
    }

    /** 按<b>声明序</b>逐条造组件(唯一创建点:工厂收 id 与 svc 成对;异常上抛 = 会话创建失败)。 */
    public void createAll(SessionServices svc) {
        Objects.requireNonNull(svc, "svc");
        for (Map.Entry<String, Binding> entry : bindingsById.entrySet()) {
            SessionComponent created = entry.getValue().snapshot().factory().create(entry.getKey(), svc);
            Objects.requireNonNull(created, "created");
            if (created.id() == null || !created.id().equals(entry.getKey())) {
                throw new IllegalStateException("会话组件 id 与绑定不符: 期望 " + entry.getKey()
                        + " 实际 " + created.id() + "(会话 " + sessionId.value() + ")");
            }
            entry.setValue(new Binding(entry.getValue().snapshot(), created));
            resourcesByComponent.put(created, new ArrayList<>());
        }
    }

    /** 声明序逐组件 awake(只解析会话级依赖;异常上抛 = 会话创建失败)。 */
    public void awakeAll() {
        liveInstances().forEach(SessionComponent::awake);
    }

    /** 声明序逐组件 start(生效;异常上抛 = 会话创建失败——与运行期隔离语义不可混淆)。 */
    public void startAll() {
        liveInstances().forEach(SessionComponent::start);
    }

    /**
     * 把组件的 {@code onEvent} 钩子桥接挂上会话总线:总线上一条订阅 →
     * {@link #dispatchEvent} 声明序逐组件派发(隔离语义在本方法内,不漏给总线的
     * 逐订阅者隔离——那是 WARN 继续而非停用)。桥接句柄进公共桶,随
     * {@link #reclaimRegisteredResources()} 回收;会话终结前组件钩子先随 stopAll 停用。
     */
    public void bridgeEventHooks() {
        SessionEventBus.Subscription bridge = bus.subscribe(SessionEvent.class, this::dispatchEvent);
        unownedResources.add(bridge);
    }

    // ── 运行期派发(逐组件 try/catch → D9 隔离;会话不解散) ───────────

    /** 每 tick 逐组件(声明序)派发 {@code update};单个抛异常 → 隔离该组件,其余照常。 */
    public void tickAll() {
        for (SessionComponent component : liveInstances()) {
            try {
                component.update();
            } catch (Throwable t) {
                quarantine(component, t, "update");
            }
        }
    }

    /** 会话事件逐组件(声明序)派发 {@code onEvent};单个抛异常 → 隔离该组件,其余照常。 */
    public void dispatchEvent(SessionEvent event) {
        if (event == null) {
            return;
        }
        for (SessionComponent component : liveInstances()) {
            try {
                component.onEvent(event);
            } catch (Throwable t) {
                quarantine(component, t, "onEvent");
            }
        }
    }

    /**
     * D9 隔离:记日志(会话 id / 组件 id / 原因)→ 打标(此后跳过其派发)→
     * {@code safeStop} → 发 {@code SessionComponentQuarantined} → <b>会话不解散</b>。
     * 与实例级的差异:实例级隔离 → {@code end()} 有回退兜底;会话级没有回退目标,
     * 停用单个策略组件是可接受的降级(如空场策略停用 = 行为退回「永不解散」并留日志)。
     */
    private void quarantine(SessionComponent component, Throwable cause, String hook) {
        if (quarantined.contains(component)) {
            return;                     // 已隔离(同实例只隔离一次的护栏)
        }
        quarantined.add(component);
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        LOG.log(Level.SEVERE, "会话组件故障隔离(会话 {0}, 组件 {1}, 钩子 {2}, 原因 {3})——组件停用,会话不解散",
                new Object[] { sessionId.value(), component.id(), hook, reason });
        safeStop(component);
        bus.publish(SessionEvent.SessionComponentQuarantined.of(sessionId, component.id(), reason));
    }

    // ── 回收(dissolve 序:逆序 stop → 统一回收登记资源) ─────────────

    /**
     * <b>逆序</b> stop(声明序的镜像;逐个 safeStop,异常不中断)。
     * <b>stop 由框架无条件调用,无单例豁免条款</b>;已隔离组件已经 safeStop 过,
     * 不重复停(与 start 的严格对称不破)。
     */
    public void stopAll() {
        List<SessionComponent> reversed = liveInstances();
        Collections.reverse(reversed);
        reversed.forEach(this::safeStop);
    }

    private void safeStop(SessionComponent component) {
        try {
            component.stop();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "会话组件 stop 异常(已隔离,继续逆序): " + component.id()
                    + "(会话 " + sessionId.value() + ")", t);
        }
    }

    /**
     * 统一回收全部登记资源(幂等):逐句柄 close,单个失败记 WARN 后继续、不中断;
     * 回收后清空登记,连调多次时第二次起为空操作(与实例级同法,§1.3 步 4)。
     */
    public void reclaimRegisteredResources() {
        closeAll(unownedResources);
        unownedResources.clear();
        for (List<AutoCloseable> bucket : resourcesByComponent.values()) {
            closeAll(bucket);
            bucket.clear();
        }
    }

    private static void closeAll(List<AutoCloseable> handles) {
        for (AutoCloseable handle : handles) {
            try {
                handle.close();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "会话组件登记资源回收失败(忽略并继续)", t);
            }
        }
    }

    // ── 资源登记与能力标签 ───────────────────────────────────────

    /**
     * 登记可回收资源句柄到发起组件名下(stop/隔离统一回收)。
     * owner 为 null 时进公共桶;owner 未注册抛 {@link IllegalStateException}(装配序错误)。
     */
    public void registerResource(SessionComponent owner, AutoCloseable handle) {
        Objects.requireNonNull(handle, "handle");
        if (owner == null) {
            unownedResources.add(handle);
            return;
        }
        List<AutoCloseable> bucket = resourcesByComponent.get(owner);
        if (bucket == null) {
            throw new IllegalStateException("资源登记的会话组件未注册: " + owner.id());
        }
        bucket.add(handle);
    }

    /** 全部在册组件的能力标签并集(A2 可用集的会话侧来源,D6;随 dissolve 消灭)。 */
    public Set<String> capabilitiesUnion() {
        Set<String> union = new LinkedHashSet<>();
        for (Binding binding : bindingsById.values()) {
            union.addAll(binding.snapshot().capabilities());
        }
        return union;
    }

    /**
     * 反查调用栈上最近的会话组件声明类,再经注册表取其实例(later/订阅句柄的归属;
     * 与实例容器的同法)。未命中返回 null(进公共桶)。
     */
    public SessionComponent callerOnStack() {
        Class<?> frame = StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE))
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(owner -> owner != SessionComponentRegistry.class
                                && SessionComponent.class.isAssignableFrom(owner))
                        .findFirst()
                        .orElse(null));
        return frame != null ? get(frame.asSubclass(SessionComponent.class)) : null;
    }

    // ── 查找口(SessionComponentLookupPort;同会话恒同一实例,★27) ─────

    /** 未隔离的全部组件实例(声明序快照;遍历期间再注册不破坏本轮)。 */
    private List<SessionComponent> liveInstances() {
        List<SessionComponent> live = new ArrayList<>(bindingsById.size());
        for (Binding binding : bindingsById.values()) {
            if (binding.instance() != null && !quarantined.contains(binding.instance())) {
                live.add(binding.instance());
            }
        }
        return live;
    }

    /** 全部组件实例(声明序;含已隔离——诊断口)。 */
    public List<SessionComponent> allInstances() {
        List<SessionComponent> instances = new ArrayList<>(bindingsById.size());
        for (Binding binding : bindingsById.values()) {
            if (binding.instance() != null) {
                instances.add(binding.instance());
            }
        }
        return instances;
    }

    /** 是否已隔离(诊断与测试断言口)。 */
    public boolean isQuarantined(SessionComponent component) {
        return quarantined.contains(component);
    }

    @Override
    public <T extends SessionComponent> T get(Class<T> type) {
        Objects.requireNonNull(type, "type");
        for (Binding binding : bindingsById.values()) {
            if (binding.instance() != null && type.isInstance(binding.instance())) {
                return type.cast(binding.instance());
            }
        }
        return null;
    }

    @Override
    public <T extends SessionComponent> List<T> getAll(Class<T> type) {
        Objects.requireNonNull(type, "type");
        List<T> found = new ArrayList<>();
        for (Binding binding : bindingsById.values()) {
            if (binding.instance() != null && type.isInstance(binding.instance())) {
                found.add(type.cast(binding.instance()));
            }
        }
        return List.copyOf(found);
    }

    @Override
    public Optional<SessionComponent> byId(String id) {
        Objects.requireNonNull(id, "id");
        Binding binding = bindingsById.get(id);
        return Optional.ofNullable(binding != null ? binding.instance() : null);
    }

    /** 声明序 id 快照(诊断与测试断言口)。 */
    public List<String> boundIds() {
        return List.copyOf(bindingsById.keySet());
    }
}
