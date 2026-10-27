package com.hydrokarbs.engine.mode;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.ServiceLoader;
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
 * {@code META-INF/services} 一行工厂全限定名)。★v1.5 收敛(2026-10-27,24 号修正案):
 * 跨插件的 ServicesManager 半边({@code ServiceRegisterEvent}/{@code ServiceUnregisterEvent}
 * 动态收录/摘除,原由 {@code listener/ProviderServiceListener} 承载)已退场——第三方
 * 模式改经 {@code MiniGameApi#registerMode} 显式注册。引擎零模式知识——任何
 * 「在引擎里 new 某工厂」的写法都是越界(§14.13)。
 *
 * <p><b>注册闸(两类失败语义不得弄反,§2.6/§12.2)</b>:
 * <ul>
 *   <li>单模式依赖校验失败({@code verifyDependencies} 抛)→ 启动期
 *       {@link #register}(ServiceLoader 半边)<b>跳过注册 + SEVERE</b>,其余模式
 *       照常(坏模板不进注册表);</li>
 *   <li>重复 id → 启动期 {@link #register} 与运行期 {@link #registerProvider}
 *       (★v1.5 起为 api 显式注册通道)一律响亮抛 {@link IllegalStateException}
 *       (装配期错误,绝不静默覆盖;原「运行期 SEVERE + 忽略后来者」口径随
 *       ServicesManager 事件通道退场而失效——显式注册可捕获,无「事件回调抛异常
 *       中断整轮派发」的顾虑)。</li>
 * </ul>
 * 整仓 0 个模式注册属合法状态(阶段早期),只打 INFO 不打错误。
 *
 * <p><b>进程级单例纪律(§2.3)</b>:注册期 build 一次、注册表持有同一实例,
 * 切换期不再重建;build 后不可追加组件(A9)——改模式 = 注册一个新 id。
 * tag 匹配({@link #forMap})是唯一的模式↔地图耦合面:模式侧 mapTags ×
 * 地图侧 tags 求交集,注册表不认识任何具体 id 或标签词汇。
 *
 * <p>线程契约:启动期主线程同步装配;运行期注册/摘除(api 显式面)同样主线程
 * (api javadoc 主线程调用口径)——本类不做并发装配。
 */
public final class MatchModeRegistry implements ModeLookup {

    private static final Logger DEFAULT_LOG = Logger.getLogger(MatchModeRegistry.class.getName());

    private final Map<String, MatchMode> modes = new LinkedHashMap<>();
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
     * 双发现(顺序是规格:本 jar 先、每个提供方都走同一注册闸):
     * <ol>
     *   <li>{@code ServiceLoader.load(MatchModeProvider.class)}——本 jar 的
     *       {@code META-INF/services} 清单;</li>
     *   <li>ServicesManager 半边——<b>历史口径(★v1.5 退场)</b>:原由
     *       {@code listener/ProviderServiceListener} 承载的先启补收录与动态
     *       收录/摘除(§13.4)随显式注册裁决撤销;第三方模式改经
     *       {@code MiniGameApi#registerMode} 注册,引擎保持零 Bukkit 静态调用,
     *       离线测试可跑。</li>
     * </ol>
     * 收尾打注册表初始化行(0 个模式属正常,INFO 级)。
     */
    public void discover() {
        // ① ServiceLoader(本 jar):SPI 清单里每行一个工厂全限定名
        ServiceLoader.load(MatchModeProvider.class).forEach(this::register);
        // ② ServicesManager 半边已退场(★v1.5;原 listener/ProviderServiceListener
        //    承载,见方法注历史口径段)——第三方注册走 MiniGameApi#registerMode 显式面。
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
     * 注册一个运行期提供方(★v1.5 起为 api 显式注册通道,唯一调用方
     * {@code MiniGameApiImpl#registerMode}):流程与 {@link #register} 同闸
     * (build(配置) → verifyDependencies → 入表),但<b>失败一律响亮上抛</b>——
     * build 抛异常(第三方工厂代码)、依赖校验失败、重复 id 均原样抛给调用方
     * (显式注册可捕获;原「运行期不上抛」口径随 ServicesManager 事件通道退场
     * 而失效——事件回调抛异常中断整轮派发的顾虑不复存在)。配置来自构造期注入的
     * 配置源(缺文件的模式以全默认参数 build,§15.2 铁律 1)。主线程调用。
     *
     * @return 入表的 {@link MatchMode} 实例(注册闸产出;调用方做快照与归属簿记)
     * @throws com.hydrokarbs.engine.component.ComponentDependencyException
     *         依赖校验失败(坏模板不进注册表,§2.6)
     * @throws IllegalStateException 重复 id(先到者保留,绝不静默覆盖)
     */
    public MatchMode registerProvider(MatchModeProvider provider) {
        MatchMode mode = provider.build(configSource.apply(provider.id()));
        mode.verifyDependencies();                     // 注册前闸:坏模板不进注册表(§2.6)
        if (modes.putIfAbsent(mode.id(), mode) != null) {
            throw new IllegalStateException("重复的模式 id: " + mode.id());
        }
        logger.log(Level.INFO, "已加载模式: {0}{1}",
                new Object[] { mode.id(), mode.meta().recordsHistory() ? "" : "(不记史)" });
        return mode;
    }

    /**
     * 按 id 摘除一个模式(★v1.5 新增;api 显式摘除通道的注册表落点,镜像
     * {@code MapRegistry#unregister} 的纯目录口径):目录中无此 id → 幂等 no-op。
     * <b>会话回退编排不在这里</b>——职责分离,由调用方(api 层)对受影响会话
     * 发起回退/解散(§3.1;镜像原 unregisterProvider 的职责分离口径)。
     *
     * @return true = 已摘除;false = 目录中无此 id(幂等 no-op)
     */
    public boolean unregister(String modeId) {
        Objects.requireNonNull(modeId, "modeId");
        return modes.remove(modeId) != null;
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
