package com.hydrokarbs.engine.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.session.SessionId;

/**
 * 会话组件贡献收录表(★v1.1-D14 通道一的进程级机制件;7.5 施工文档步骤 6;
 * ★v1.5 起唯一调用方 = {@code MiniGameApiImpl#registerSessionComponents} 显式面)。
 *
 * <p><b>职责只有两件</b>:①收录——干跑合并校验(合并既有贡献 + 新贡献,经
 * scratch {@link SessionComponentRegistry} 走与真实装配同一套 bind 查重 +
 * {@code verifyDependencies} 算法)通过才落地;②按提供方身份摘除(插件 disable
 * 兜底,后续会话不再合并;已装配会话不受影响——无运行期拆卸,D14)。
 * 会话编排不在这里(镜像摘除回退先例:职责分离,归 api 层)。
 *
 * <p><b>收录闸失败语义</b>:{@link #register} 校验失败响亮上抛给调用方
 * (★v1.5 显式注册可捕获——onEnable 注册失败 = 拒载信号;原事件路径「调用方
 * catch 后 SEVERE 拒收录、会话照常」口径随 ServicesManager 通道退场);
 * <b>失败不留半收录态</b>:先按身份摘除既有条目再干跑,失败时新贡献与旧条目
 * 均不入表(与「坏贡献拒收录」口径一致,重注册场景语义自洽)。
 *
 * <p><b>键形态</b>:提供方键 = {@code Object} 身份(★v1.5 生产 = 调用方插件实例
 * {@code org.bukkit.plugin.Plugin}——显式注册的 provider 参数与插件 disable 兜底
 * 按同一插件实例摘除,14.38/D14;IdentityHashMap 同款「按实例身份」纪律,
 * 7.2 要点 4)。本类零 Bukkit 依赖(离线测试可传任意身份对象),
 * {@code map} 包纯模型纪律同源。
 *
 * <p>线程契约:收录/摘除/合并全部主线程(插件生命周期事件在主线程派发,
 * api javadoc 主线程调用口径);普通集合不加锁。
 */
public final class SessionComponentContributionRegistry {

    /** 收录条目:提供方身份键 + 其贡献的描述符列表(注册序保持)。 */
    private record Entry(Object providerKey, List<SessionComponent.Specification<?>> specs) {
    }

    /** 收录表(注册序;摘除按身份扫描——提供方数量小,O(n) 摘除无虞)。 */
    private final List<Entry> accepted = new ArrayList<>();

    /**
     * 收录一个提供方的贡献(D14 通道一):先按身份摘除既有条目(重注册幂等)→
     * 全量干跑合并校验(scratch 注册表:组件 id 查重 + 必需依赖可满足)→
     * 通过才落地。校验失败响亮上抛给调用方(★v1.5 显式注册可捕获)。
     *
     * <p><b>freeze 副作用注</b>:干跑经 {@code spec.freeze()} 产快照——描述符
     * 冻结与真实装配路径同款(每次 {@code create} 前框架本就逐 Spec freeze,
     * 共享 SPEC 常量跨会话互不串改由 Snapshot 结构保证);对被拒贡献的描述符,
     * 冻结后仅「声明方法拒改」(声明发生在其构造期),无行为影响。
     */
    public void register(Object providerKey, List<SessionComponent.Specification<?>> specs) {
        Objects.requireNonNull(providerKey, "providerKey");
        Objects.requireNonNull(specs, "specs");
        for (SessionComponent.Specification<?> spec : specs) {
            Objects.requireNonNull(spec, "spec");
        }
        unregister(providerKey);                    // 重注册幂等:同身份旧条目先摘(合并语义见类注)
        List<SessionComponent.Specification<?>> merged = merged();
        merged.addAll(specs);
        dryRun(merged);                             // 查重(bind 撞 id 抛)+ 依赖校验(缺必需依赖抛)
        accepted.add(new Entry(providerKey, List.copyOf(specs)));
    }

    /**
     * 摘除一个提供方的全部贡献(身份键;幂等——未收录返回 false,静默)。
     * provider 插件 disable → 后续会话不再合并(D14;已装配会话不受影响)。
     */
    public boolean unregister(Object providerKey) {
        Objects.requireNonNull(providerKey, "providerKey");
        return accepted.removeIf(entry -> entry.providerKey() == providerKey);
    }

    /**
     * 已收录贡献的合并视图(注册序拼接;每次调用新拷贝——收录表可变,
     * 不外露内部列表,快照纪律同 {@code MatchModeRegistry#all})。
     * {@code createSession(SessionRecipe)} 的合并序 = 本表(注册序)→ recipe.extra。
     */
    public List<SessionComponent.Specification<?>> merged() {
        List<SessionComponent.Specification<?>> merged = new ArrayList<>();
        for (Entry entry : accepted) {
            merged.addAll(entry.specs());
        }
        return merged;
    }

    /** 是否无任何收录(诊断与测试断言口)。 */
    public boolean isEmpty() {
        return accepted.isEmpty();
    }

    /**
     * 干跑合并校验:scratch {@link SessionComponentRegistry}(每会话一份的机制件
     * 在此以「不落地的影子会话」复用——装配算法只有一份,不写第二套)逐条
     * {@code bind}(组件 id 查重,同 id 抛 {@code IllegalStateException})+
     * {@code verifyDependencies}(缺必需依赖抛 {@link ComponentDependencyException};
     * 算法逐行复用:自己不算自己的提供者、可选缺失不报错、环不检查)。
     */
    private static void dryRun(List<SessionComponent.Specification<?>> specs) {
        SessionComponentRegistry scratch = new SessionComponentRegistry(
                new SessionId("contribution-dry-run"), new SessionEventBus());
        for (SessionComponent.Specification<?> spec : specs) {
            scratch.bind(deriveComponentId(spec), spec.freeze());
        }
        scratch.verifyDependencies();
    }

    /**
     * 组件 id 推导(镜像 {@code SessionServiceImpl#deriveComponentId} 的单一落点口径:
     * 供给面类型简单名)——干跑与真实装配必须同源,否则查重口径漂移;两处任一改动
     * 须同步(镜像落定,7.5 施工决策:「静态化或 scratch 实例,按代码现状落定」)。
     */
    private static String deriveComponentId(SessionComponent.Specification<?> spec) {
        return spec.providedType().getSimpleName();
    }
}
