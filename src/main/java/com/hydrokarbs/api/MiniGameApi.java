package com.hydrokarbs.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.hydrokarbs.api.view.MapInfo;
import com.hydrokarbs.api.view.MatchModeInfo;
import com.hydrokarbs.api.view.SessionInfo;
import com.hydrokarbs.engine.component.SessionComponent;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.LeaveReason;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.SwitchResult;
import com.hydrokarbs.map.MapDefinition;

/**
 * 本框架的对外公共面。经 Bukkit ServicesManager 暴露(07-公共API §7.1/§7.2)——
 * 与本框架消费任一宿主服务的 ServicesManager 是同一机制,同一套纪律换个方向(§7 章首)。
 *
 * <p><b>v1 冻结面(7.1,2026-10-25;8 方法 = 查询 3 + 控制 2 + 目录 3)</b>:
 * 方法集冻结、<b>只增不改</b>(§7.2;★19 {@code MiniGameApiSurfaceTest} 反射守卫,
 * 删/改签名即测试红)。引擎契约类型({@code SwitchResult}/{@code MatchModeSelection}/
 * {@code Handoff})直接 import 自 {@code engine.session},不复制(7.1 步骤 1;
 * engine 类型被 {@code apiDependsOnlyOnEngine} 放行,§16.2)。
 *
 * <p><b>7.5 增量(2026-10-25,G1 闸门;API_VERSION = 3,16 方法)</b>:
 * 成员操作(joinSession/leaveSession,D2)、会话事件订阅(subscribeSessionEvents,
 * D3)、能力标签(D4,★v1.7 退场)、会话配方建会话
 * (createSession(SessionRecipe),D14 通道二)、地图注册(registerMap/
 * unregisterMap,★v1.2)——只增不改,v1 面 8 方法签名一字不动(★44
 * {@code ApiSurfaceTest} 白名单与签名快照双断言守卫;14.35~14.39 跨 jar 纪律见各方法注)。
 *
 * <p><b>战绩面悬置注(★v1.3/21-D1/R1;回填裁决登记 21 §6-Q1)</b>:§7.1 的
 * {@code stats(UUID,String)}/{@code top(String,String,int)} 两方法随持久化整体
 * 出框架而悬置——归宿主线消费,签名类型 {@code PlayerStats}/{@code LeaderboardRow}
 * 已随 L5 data/model 出框架。方法集冻结「只增不改」,本注不改任何既有签名;
 * 缺席语义与类型归宿待宿主线持久化落定后回填。
 *
 * <p><b>★v1.4 收敛(2026-10-27;API_VERSION = 4,冻结面计数 16 → 15 方法 =
 * 查询 3 + 控制 1 + 成员 2 + 订阅 1 + 能力 2 + 建会话 1 + 地图 2 + 目录 3)</b>:
 * 两参 {@code createSession(String, String)} 重载经用户裁决删除(破坏性收敛,
 * 调用方迁移 {@link SessionRecipe} 形态),公共面建会话仅剩
 * {@link #createSession(SessionRecipe)} 唯一形态;地图注册通道(★v1.2)维持不动。
 * 「只增不改」纪律由 15 方法清单续行守卫(★19/★44,冻结测试与版本常量随本裁决合法更新)。
 *
 * <p><b>★v1.6 显式注册收敛(2026-10-27;API_VERSION = 5,冻结面计数 15 → 19 方法 =
 * 查询 3 + 控制 1 + 成员 2 + 订阅 1 + 能力 2 + 建会话 1 + 地图 2 +
 * <b>模式显式注册 2 + 会话组件显式注册 2</b> + 目录 3;24 号修正案裁决)</b>:
 * 模式与会话组件改经本面显式注册——跨插件 ServicesManager 提供方通道
 * ({@code register(MatchModeProvider.class, …)} + {@code ServiceRegisterEvent}/
 * {@code ServiceUnregisterEvent} 动态收录/摘除)退场,注册期「响亮/不上抛」双口径
 * 合一为响亮(显式调用方可捕获,事件回调抛异常中断派发的顾虑随通道消失);
 * {@code SessionComponentProvider} SPI 接口删除(破坏性变更,消费方迁移
 * {@link #registerSessionComponents});{@code MatchModeProvider} 保留为
 * {@link #registerMode} 的载荷类型(本 jar ServiceLoader 半边出厂模式不动)。
 * 提供方插件 disable 的框架侧兜底(14.38)对注册物各族继续有效,消费方
 * onDisable 显式反注册仍是纪律。「只增不改」纪律由 19 方法清单续行守卫
 * (★19/★44,冻结测试与版本常量随本裁决合法更新)。
 *
 * <p><b>★v1.7 能力标签机制退场(2026-10-27;API_VERSION = 6,冻结面计数 19 → 17 方法 =
 * 查询 3 + 控制 1 + 成员 2 + 订阅 1 + 建会话 1 + 地图 2 + 模式显式注册 2 +
 * 会话组件显式注册 2 + 目录 3;25 号修正案裁决)</b>:
 * ★v1.1-D4 的能力标签两法与全链字符串能力标签机制
 * (注册表/A2 校验/模式与快照分量)删除——字符串声明不严谨;外部能力改由
 * <b>会话组件桥接</b>表达:桥接会话组件经 {@link #registerSessionComponents}/
 * {@link SessionRecipe} 进会话,消费方组件 {@code requires/requiresOptional}
 * 类型化声明,装配期响亮、运行期 quarantine(机制在框架内既有,本裁决删的是
 * 叠在其上的字符串平行层)。「只增不改」纪律由 17 方法清单续行守卫(★19/★44,
 * 冻结测试与版本常量随本裁决合法更新)。
 *
 * <p><b>A9 三限制(§0.2-A9/§7.2)</b>:①聚合根 {@code build()} 后不可变;
 * ②第三方不能给已注册模式追加组件或改组件表;③要「变体」就注册一个新 id——
 * 杜绝运行期装饰带来的加载顺序地狱。
 *
 * <p><b>线程契约(javadoc 冻结;依据 §10.5 逐行 + §3.2 javadoc + §14.1)</b>:
 *
 * <table border="1">
 *   <caption>线程契约</caption>
 *   <tr><th>方法</th><th>线程</th><th>依据</th></tr>
 *   <tr><td>sessionOf/session/sessions/modes/maps/mode/createSession(recipe)/
 *       joinSession/leaveSession/registerMap/unregisterMap/registerMode/unregisterMode/
 *       registerSessionComponents/unregisterSessionComponents</td>
 *       <td>主线程调用(同步返回快照/结果)</td>
 *       <td>会话/注册表结构非线程安全(§10.5;Bukkit 事件主线程派发)</td></tr>
 *   <tr><td>switchMode</td>
 *       <td>任意线程发起;Impl 内部回主线程,future 在主线程完成</td>
 *       <td>§14.1 回调线程纪律(§3.2「主线程发起」)</td></tr>
 *   <tr><td>subscribeSessionEvents</td>
 *       <td>订阅调用主线程;回调发生在事件发布线程(会话事件发布在主线程)</td>
 *       <td>§11.1 同步派发;D3 顺序承诺</td></tr>
 * </table>
 */
public interface MiniGameApi {

    /**
     * API 面版本常量(★v1.1-D7 版本协商;只增不减):v1.0 = 1;v1.1 = 2;
     * v1.2 = 3(地图注册通道);v1.3 维持 3(战绩面悬置注,不改任何签名);
     * v1.4 = 4(两参 {@code createSession} 重载删除——建会话公共面仅剩
     * {@link #createSession(SessionRecipe)} 形态;地图注册通道维持);
     * v1.6 = 5(显式注册收敛:模式/会话组件经 {@link #registerMode}/
     * {@link #registerSessionComponents} 注册,ServicesManager 提供方通道与
     * {@code SessionComponentProvider} SPI 删除);
     * v1.7 = 6(能力标签机制退场:外部能力 = 会话组件桥接,25 号修正案裁决)。
     * 消费方 {@code onEnable} <b>首行</b>检查
     * {@code api == null || api.API_VERSION < REQUIRED} → SEVERE +
     * {@code setEnabled(false)}(拒载不崩服,14.36);框架 onEnable 日志输出版本。
     */
    int API_VERSION = 6;

    // ── 查询(不可变快照;现取不缓存,14.31 同款纪律) ────────────────────────

    /** 玩家 → 所属会话快照(名册查询);未入任何会话 → empty。主线程调用。 */
    Optional<SessionInfo> sessionOf(UUID player);

    /** 按会话 id 取快照(只读查询);不存在 → empty。主线程调用。 */
    Optional<SessionInfo> session(String sessionId);

    /** 全部存活会话快照(引擎遍历序);空表合法(零会话起服为合法状态,§3.6)。主线程调用。 */
    List<SessionInfo> sessions();

    // ── 控制(与 /sm 命令同权,§7.1) ────────────────────────────────────────

    /**
     * 切换某会话的模式——{@code SessionService#switchMode} 的受控暴露(§3.2)。
     *
     * <p>失败语义(7.1 步骤 6 委托表):会话 id 不在注册表 → future 完成
     * {@code Rejected(MODE_NOT_FOUND, "session … 不存在")}(「要找的 id 不在注册表」
     * 语义,§3.2 码表);其余拒绝经引擎既有三级回退管线原样透传
     * ({@code SWITCH_IN_PROGRESS}/{@code REJECTED_BY_TARGET_MODE})。
     * 空白 id / null target → future 异常完成({@code SessionId} 响亮口径);
     * future 永不悬挂(成功/拒绝/异常后必完成,§3.2)。
     *
     * <p>线程:任意线程发起;Impl 内部回主线程(§3.2 主线程口径),future 完成点
     * 收敛在主线程(§14.1)——回调里做玩家操作请自行再回主线程。
     */
    CompletableFuture<SwitchResult> switchMode(String sessionId, MatchModeSelection target,
                                               Handoff payload);

    /**
     * 创建会话(★v1.1-D14 通道二:会话配方,单次贡献会话组件,§7.5)。
     * 合并序 = 已收录 provider 贡献(注册序,D14 通道一)→ {@code recipe.extraComponents}
     * (装配点唯一不变;模式仍无权贡献,无运行期增删)。
     *
     * <p>失败语义(装配期响亮失败,代价低,§7.3):{@code initialModeId} 未注册 /
     * 组件 id 查重冲突 / 缺必需依赖 → 响亮抛,会话不创建、不留半装配态
     * (与既有会话创建语义一致);随 {@code dissolve} 全灭(★52)。
     * 本方法是公共 API 建会话的唯一形态(★v1.4:两参重载已删除)。主线程调用。
     */
    SessionInfo createSession(SessionRecipe recipe);

    // ── 成员操作(★v1.1-D2;★42) ────────────────────────────────────────────

    /**
     * 将玩家加入会话——{@code SessionService#join} 的受控暴露(§3.2;含
     * {@code JoinAdmission} 裁决,D2)。
     *
     * <p>失败语义(引擎既有裁决原样透传):会话不存在 / 正在解散 / 实例入场裁决
     * 拒绝 → {@code JoinResult.Rejected}(detail 人读;调用方兜底提示,★42);
     * 成员已在册 → 幂等重入(名册登记幂等,§3.2)。
     * 主线程调用。权限/审计口径未决(19-§7-Q3:倾向第一版无权限闸)。
     */
    JoinResult joinSession(String sessionId, UUID player);

    /**
     * 将玩家移出其当前会话;不在任何会话 = no-op(幂等,D2)。主线程调用。
     * {@code reason} 决定离开语义(QUIT = 断线宽限在册 / KICK = 立即移除,§3.2)。
     */
    void leaveSession(UUID player, LeaveReason reason);

    // ── 会话事件订阅(★v1.1-D3;★43) ────────────────────────────────────────

    /**
     * 订阅会话事件(八事件快照族,§3.1/D3)。返回句柄,{@code close()} 注销
     * (14.38:句柄只在插件实例字段中持有,onDisable 显式 close);订阅者异常被
     * 隔离(镜像总线既有隔离,记 WARN 后其余订阅者照常)。
     *
     * <p>顺序承诺:单会话内事件有序;跨插件订阅者间不承诺送达顺序。
     * 快照不可变、不含活会话/活组件引用(★29 现取不缓存纪律);实例事件
     * ({@code MatchModeInstanceEvent})不经本面暴露(19-§7-Q5 维持不开放)。
     */
    EventSubscription subscribeSessionEvents(Consumer<SessionEventView> handler);

    // ── 地图注册(★v1.2;★54) ───────────────────────────────────────────────

    /**
     * 注册地图(声明 = 纯静态 {@link MapDefinition},直接交成品——与模式必须交
     * 工厂相对,20-D1)。注册期校验(响亮抛,20-D3):id 查重(重复点名先到者)、
     * tags、世界模板目录存在性({@code maps/templates/<mapId>/},缺失响亮抛带路径;
     * 14.39:先解包后注册)。成功返回 {@link MapInfo} 快照并进 {@link #maps()}
     * 目录,可被 {@code switchMode} 选中。主线程调用。
     */
    MapInfo registerMap(org.bukkit.plugin.Plugin provider, MapDefinition map);

    /**
     * 摘除地图(20-D4)。该图为任一存活会话当前实例所用 → 摘除被拒(detail 点名
     * 会话——图随会话打完自然退出,或 dissolve 后再摘;这是特性不是障碍,20-D4)。
     * 幂等:已不在目录 = no-op。提供方插件 disable → 框架兜底摘除(正用则保留并
     * WARN)。主线程调用。
     */
    void unregisterMap(org.bukkit.plugin.Plugin provider, String mapId);

    // ── 模式显式注册(★v1.6;24 号修正案:ServicesManager 提供方通道退场) ──

    /**
     * 注册模式工厂(★v1.6 显式注册通道;07 §7.2 表「第三方注册模式」行的本面落点)。
     * <b>注册工厂,不是注册成品</b>(§13.4):{@link MatchModeProvider} 交声明
     * 不交实例——聚合根由框架以自己的配置源 {@code build(cfg)} 产出(modes/&lt;id&gt;.yml
     * 的生命周期归框架,§10.2;工厂不持成品状态),注册闸
     * (build → verifyDependencies → id 查重)<b>全响亮</b>:坏声明/重复 id 原样抛给
     * 调用方(onEnable 注册失败 = 拒载信号;原运行期事件回调的「不上抛」口径随
     * ServicesManager 通道退场)。成功返回模式快照并进 {@link #modes()} 目录,
     * 可被 {@code switchMode} 选中。
     *
     * <p>A9 三限制(§0.2-A9/§7.2):①聚合根 build 后不可变;②第三方不能给已注册
     * 模式追加组件或改组件表;③要「变体」就注册一个新 id——杜绝运行期装饰带来的
     * 加载顺序地狱。工厂与其组件的第三方依赖白名单见 {@link MatchModeProvider}
     * javadoc(§13.4 约束段)。
     *
     * <p>{@code provider} 用于归属簿记:disable 兜底按提供方摘除(14.38);
     * {@link #unregisterMode} 只能摘自己注册的 id。主线程调用。
     */
    MatchModeInfo registerMode(org.bukkit.plugin.Plugin provider, MatchModeProvider factory);

    /**
     * 摘除本提供方注册的一个模式(★v1.6)。归属簿记在框架侧:只准摘自己注册的
     * id——id 属于其他提供方(含本 jar 出厂模式)→ {@code IllegalStateException}
     * 响亮拒;id 不存在 → 幂等 no-op。摘除后执行<b>摘除回退编排</b>(§13.4/§3.2
     * 二级):逐存活会话当前模式 ∈ 被摘集——{@code session.fallback-mode} 已配置且
     * 已注册 → force 切换回退(豁免 min-dwell,§3.5);否则 {@code FALLBACK_DISSOLVE}
     * 解散;回退被拒({@code SWITCH_IN_PROGRESS})WARN 放弃不自旋(§14.17)。
     * 主线程调用。
     */
    void unregisterMode(org.bukkit.plugin.Plugin provider, String modeId);

    // ── 会话组件显式注册(★v1.6;D14 通道一的显式面) ─────────────────────

    /**
     * 注册会话组件贡献(★v1.6 显式注册通道;D14 通道一,07 §7.3「受限开放」):
     * 提供宿主级会话策略(自定义重连/宽限/准入记账等),<b>模式仍无权贡献</b>
     * (v4-D4 不变)、无运行期增删(贡献只发生在会话创建时,装配点唯一不变)。
     * 干跑合并校验(组件 id 查重/必需依赖可满足)失败<b>响亮上抛</b>给调用方
     * (显式注册可捕获;注册闸只有一份——原事件通道「SEVERE 拒收录、会话照常」
     * 口径随通道退场);通过后所有后续 {@link #createSession} 合并其贡献(注册序)。
     * 同一提供方重复注册 = 幂等重收录(旧条目先摘再干跑,不留半收录态)。
     * 主线程调用。
     */
    void registerSessionComponents(org.bukkit.plugin.Plugin provider,
                                   List<SessionComponent.Specification<?>> specs);

    /**
     * 摘除本提供方的全部会话组件贡献(★v1.6;幂等——未注册 = no-op):后续会话
     * 不再合并;已装配会话不受影响(无运行期拆卸,随 dissolve 自然消亡,D14)。
     * 主线程调用。
     */
    void unregisterSessionComponents(org.bukkit.plugin.Plugin provider);

    // ── 目录(顺序 = 注册序,不排序不过滤——14.21「大厅特判」反弹规避) ─────
    //    第三方模式经 registerMode 显式注册(★v1.6;原 ServicesManager 收录口径退场)
    //    入目录后服主经本组方法可见,与其他模式同列、无特判(§7.2/§13.4;框架不设
    //    出厂模式,21-D3)。

    /** 全部已注册模式快照(注册序,含第三方模式;空表合法(0 个模式属正常))。主线程调用。 */
    List<MatchModeInfo> modes();

    /** 全部已注册地图快照(注册序;空表合法)。主线程调用。 */
    List<MapInfo> maps();

    /** 按模式 id 取快照;缺失 → empty(不抛)。主线程调用。 */
    Optional<MatchModeInfo> mode(String modeId);
}
