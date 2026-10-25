package com.hydrokarbs.engine.mode;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.hydrokarbs.engine.component.ComponentDependencyException;
import com.hydrokarbs.engine.port.ModeLookup;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.map.MapDefinition;

/**
 * 模式注册表:双发现 + 注册闸(12-代码骨架 §12.2 骨架逐行落地;施工计划 2.2)。
 *
 * <p><b>发现是声明式的</b>:① {@link java.util.ServiceLoader}(本 jar,
 * {@code META-INF/services} 一行工厂全限定名)② ServicesManager(跨插件,
 * 第三方 {@code register(MatchModeProvider.class, ...)} + 监听
 * {@code ServiceRegisterEvent} 动态收录/摘除)。引擎零模式知识——任何
 * 「在引擎里 new 某工厂」的写法都是越界(§14.13)。
 *
 * <p><b>注册闸(两类失败语义不得弄反,§2.6/§12.2;7.2 起双口径并存)</b>:
 * <ul>
 *   <li>单模式依赖校验失败({@code verifyDependencies} 抛)→ <b>跳过注册 +
 *       SEVERE</b>,其余模式照常(坏模板不进注册表);</li>
 *   <li>重复 id → <b>口径分半</b>:启动期 {@link #register}(ServiceLoader 半边)
 *       响亮抛 {@link IllegalStateException}(装配期错误,绝不静默覆盖);
 *       运行期 {@link #registerProvider}(ServicesManager 半边)SEVERE +
 *       忽略后来者——Bukkit 事件回调抛异常会中断整轮事件派发,不能响亮
 *       (7.2 施工文档要点 3)。</li>
 * </ul>
 * 整仓 0 个模式注册属合法状态(阶段早期),只打 INFO 不打错误。
 *
 * <p><b>进程级单例纪律(§2.3)</b>:注册期 build 一次、注册表持有同一实例,
 * 切换期不再重建;build 后不可追加组件(A9)——改模式 = 注册一个新 id。
 * tag 匹配({@link #forMap})是唯一的模式↔地图耦合面:模式侧 mapTags ×
 * 地图侧 tags 求交集,注册表不认识任何具体 id 或标签词汇。
 *
 * <p>线程契约:启动期主线程同步装配;运行期收录/摘除同样主线程
 * (ServicesManager 事件在主线程派发,§7.2 要点 5)——本类不做并发装配。
 */
public final class MatchModeRegistry implements ModeLookup {

    private static final Logger DEFAULT_LOG = Logger.getLogger(MatchModeRegistry.class.getName());

    private final Map<String, MatchMode> modes = new LinkedHashMap<>();
    /** provider → 其注册的模式 id 集(摘除用;IdentityHashMap 按实例身份,7.2 要点 4)。 */
    private final Map<MatchModeProvider, Set<String>> providerIds = new IdentityHashMap<>();
    private final Function<String, MatchModeConfig> configSource;
    private final Logger logger;

    public MatchModeRegistry(Function<String, MatchModeConfig> configSource) {
        this(configSource, DEFAULT_LOG);
    }

    /** 日志注入构造口(离线测试捕获 SEVERE/INFO 断言用;生产行为与单参构造一致)。 */
    public MatchModeRegistry(Function<String, MatchModeConfig> configSource, Logger logger) {
        this.configSource = configSource;
        this.logger = logger;
    }

    /**
     * 双发现(顺序是规格:本 jar 先、跨插件后,每个提供方都走同一注册闸):
     * <ol>
     *   <li>{@code ServiceLoader.load(MatchModeProvider.class)}——本 jar 的
     *       {@code META-INF/services} 清单;</li>
     *   <li>ServicesManager 半边——<b>由 {@code listener/ProviderServiceListener}
     *       承载(7.2)</b>:其构造期 {@code loadAll(MatchModeProvider.class)} 补收录
     *       先启第三方,此后 {@code ServiceRegisterEvent}/{@code ServiceUnregisterEvent}
     *       动态收录与摘除(§13.4)。引擎保持零 Bukkit 静态调用,离线测试可跑
     *       (7.1 注释预留就此落地)。</li>
     * </ol>
     * 收尾打注册表初始化行(0 个模式属正常,INFO 级)。
     */
    public void discover() {
        // ① ServiceLoader(本 jar):SPI 清单里每行一个工厂全限定名
        ServiceLoader.load(MatchModeProvider.class).forEach(this::register);
        // ② ServicesManager(跨插件)——7.2 落地:初始 loadAll 与动态收录/摘除
        //    由 listener/ProviderServiceListener 承载(先启补收录 + ServiceRegisterEvent
        //    /ServiceUnregisterEvent,§13.4);本类保持零 Bukkit 静态调用。
        logger.log(Level.INFO, "模式注册表初始化完成:已注册 {0} 个模式", modes.size());
    }

    /**
     * 注册一个提供方(注册闸,启动期口径):{@code build(配置)} →
     * {@code verifyDependencies()} → 入表。依赖校验失败 → SEVERE + 跳过;
     * 重复 id → {@link IllegalStateException}(响亮失败,绝不静默覆盖)。
     * 配置来自构造期注入的配置源(缺文件的模式以全默认参数 build,§15.2 铁律 1)。
     */
    public void register(MatchModeProvider provider) {
        MatchMode mode = provider.build(configSource.apply(provider.id()));
        try {
            mode.verifyDependencies();                     // ★ 注册前闸:坏模板不进注册表(§2.6)
        } catch (ComponentDependencyException e) {
            logger.log(Level.SEVERE, "模式 " + provider.id() + " 依赖校验失败,跳过注册", e);
            return;
        }
        if (modes.putIfAbsent(mode.id(), mode) != null) {
            throw new IllegalStateException("重复的模式 id: " + mode.id());
        }
        logger.log(Level.INFO, "已加载模式: {0}{1}",
                new Object[] { mode.id(), mode.meta().recordsHistory() ? "" : "(不记史)" });
    }

    /**
     * 注册一个运行期提供方(ServicesManager 半边,运行期口径;7.2):流程与
     * {@link #register} 同闸,但<b>失败一律不上抛</b>——
     * <ul>
     *   <li>build 抛异常(第三方工厂代码)或依赖校验失败 → SEVERE + 跳过 +
     *       不进注册表(§2.6 失败形态;照抄角色系统装配器的「装配即校验」纪律,
     *       §7.2 表——禁词纪律:不以字面引用其类名,EnginePurityTest 守);</li>
     *   <li>重复 id → SEVERE + <b>忽略后来者</b>(Bukkit 事件回调抛异常会中断整轮
     *       事件派发,不能响亮——与启动期 {@link #register} 的响亮抛构成双口径,
     *       7.2 施工文档要点 3)。</li>
     * </ul>
     * 成功则记 {@code provider → 模式 id} 映射(供 {@link #unregisterProvider}
     * 按提供方摘除)。<b>不按提供方来源分支</b>(§14.12):本 jar 与第三方经本方法
     * 注册后一律同构。主线程调用(§7.2 要点 5)。
     */
    public void registerProvider(MatchModeProvider provider) {
        MatchMode mode;
        try {
            mode = provider.build(configSource.apply(provider.id()));
            mode.verifyDependencies();                     // 注册前闸:坏模板不进注册表(§2.6)
        } catch (RuntimeException e) {                     // build 异常与依赖校验失败同路径:不上抛
            logger.log(Level.SEVERE, "模式 " + provider.id() + " 注册失败,跳过(运行期口径不上抛)", e);
            return;
        }
        if (modes.putIfAbsent(mode.id(), mode) != null) {
            logger.log(Level.SEVERE, "重复的模式 id: {0}(运行期忽略后来者)", mode.id());
            return;
        }
        providerIds.put(provider, Set.of(mode.id()));
        logger.log(Level.INFO, "已加载模式: {0}{1}(ServicesManager 收录)",
                new Object[] { mode.id(), mode.meta().recordsHistory() ? "" : "(不记史)" });
    }

    /**
     * 摘除一个提供方已注册的全部模式(ServicesManager 半边;7.2):按
     * {@code provider → 模式 id} 映射(IdentityHashMap,按实例身份——同一实例只
     * build 一次,按 equals 混淆不同实例会摘错,7.2 要点 4)逆操作摘除。
     *
     * @return 被摘的模式 id 集(未注册过的提供方 → 空集);<b>会话回退编排不在这里</b>
     *         ——职责分离,由调用方(listener 层)对受影响会话发起回退/解散(§3.1)。
     */
    public Set<String> unregisterProvider(MatchModeProvider provider) {
        Set<String> ids = providerIds.remove(provider);
        if (ids == null) {
            return Set.of();
        }
        for (String id : ids) {
            modes.remove(id);
        }
        logger.log(Level.INFO, "已摘除模式: {0}(ServicesManager 摘除)", ids);
        return ids;
    }

    /** 按 id 取模式;缺失响亮抛(切换准备阶段「目标不存在」拒绝的上游)。 */
    @Override
    public MatchMode require(String modeId) {
        MatchMode mode = modes.get(modeId);
        if (mode == null) {
            throw new NoSuchElementException("未注册的模式 id: " + modeId
                    + "(已注册: " + modes.keySet() + ")");
        }
        return mode;
    }

    /** 全部已注册模式(声明序,不可变快照)。 */
    @Override
    public Collection<MatchMode> all() {
        return Collections.unmodifiableCollection(modes.values());
    }

    /**
     * 该地图可用的模式:模式侧 mapTags × 地图侧 tags 交集非空即匹配
     * (§12.2;空交集合法——地图适配的强制点在切换检查,不在注册表)。
     * TODO(15-适配 §15.2):{@code supported-modes} 显式收窄字段后续阶段评估。
     */
    public List<MatchMode> forMap(MapDefinition map) {
        return modes.values().stream()
                .filter(mode -> !Collections.disjoint(mode.mapTags(), map.tags()))
                .toList();
    }
}
