package com.hydrokarbs.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.hydrokarbs.api.view.MapInfo;
import com.hydrokarbs.api.view.MatchModeInfo;
import com.hydrokarbs.api.view.SessionInfo;
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
 * D3)、能力标签(registerCapability/capabilities,D4)、会话配方建会话
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
 * <p><b>A9 三限制(§0.2-A9/§7.2)</b>:①聚合根 {@code build()} 后不可变;
 * ②第三方不能给已注册模式追加组件或改组件表;③要「变体」就注册一个新 id——
 * 杜绝运行期装饰带来的加载顺序地狱。
 *
 * <p><b>线程契约(javadoc 冻结;依据 §10.5 逐行 + §3.2 javadoc + §14.1)</b>:
 *
 * <table border="1">
 *   <caption>线程契约</caption>
 *   <tr><th>方法</th><th>线程</th><th>依据</th></tr>
 *   <tr><td>sessionOf/session/sessions/modes/maps/mode/createSession(两参与
 *       recipe)/joinSession/leaveSession/registerCapability/capabilities/
 *       registerMap/unregisterMap</td>
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
     * v1.2 = 3(地图注册通道)。消费方 {@code onEnable} <b>首行</b>检查
     * {@code api == null || api.API_VERSION < REQUIRED} → SEVERE +
     * {@code setEnabled(false)}(拒载不崩服,14.36);框架 onEnable 日志输出版本。
     */
    int API_VERSION = 3;

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
     * 创建会话(arena 拓扑下第三方可开私设竞技场,§7.1/§3.6)。返回新会话快照
     * (首切换尚在途,{@code modeId}/{@code mapId} 为空串)。
     *
     * <p>参数口径:{@code initialModeId} 未注册 → <b>响亮抛</b>(模式注册表
     * {@code require} 的既有口径,§12.1);{@code initialMapId} 非空白 = 会话地图池
     * 单元素(私设竞技场钉在该图,首切换经池过滤命中,§3.2-A3),空白 = 空池自动挑图。
     * 会话 id 由框架生成(调用方不经 {@code sessions()} 查询即得)。
     * 主线程调用。
     */
    SessionInfo createSession(String initialModeId, String initialMapId);

    /**
     * 创建会话(★v1.1-D14 通道二:会话配方,单次贡献会话组件,§7.5)。
     * 合并序 = 已收录 provider 贡献(注册序,D14 通道一)→ {@code recipe.extraComponents}
     * (装配点唯一不变;模式仍无权贡献,无运行期增删)。
     *
     * <p>失败语义(装配期响亮失败,代价低,§7.3):{@code initialModeId} 未注册 /
     * 组件 id 查重冲突 / 缺必需依赖 → 响亮抛,会话不创建、不留半装配态
     * (与既有会话创建语义一致);随 {@code dissolve} 全灭(★52)。
     * 既有两参重载保留,等价于空 recipe。主线程调用。
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

    // ── 能力标签(★v1.1-D4) ─────────────────────────────────────────────────

    /**
     * 注册能力标签(提供方 = 本插件,如角色/经济适配;★32 跨 jar 侧正式化,D4)。
     * 幂等(重复注册无副作用);标签空白响亮抛(引擎既有校验);
     * <b>提供方插件 disable 时自动摘除</b>(D8;框架侧 {@code PluginDisableEvent}
     * 兜底,14.38——消费方无需自建善后,但 {@code onDisable} 显式反注册仍是纪律)。
     * 主线程调用。
     */
    void registerCapability(org.bukkit.plugin.Plugin provider, String tag);

    /** 当前全部可用能力标签快照(引擎机制件的只读面;含服务/L1 侧注册的标签)。主线程调用。 */
    Set<String> capabilities();

    // ── 地图注册(★v1.2;★54) ───────────────────────────────────────────────

    /**
     * 注册地图(声明 = 纯静态 {@link MapDefinition},直接交成品——与模式必须交
     * 工厂相对,20-D1)。注册期校验(响亮抛,20-D3):id 查重(与文件式同一
     * 命名空间,重复点名先到者)、tags、世界模板目录存在性
     * ({@code maps/templates/<mapId>/},缺失响亮抛带路径;14.39:先解包后注册)。
     * 成功返回 {@link MapInfo} 快照并进 {@link #maps()} 目录,可被 {@code switchMode}
     * 选中。主线程调用。
     */
    MapInfo registerMap(org.bukkit.plugin.Plugin provider, MapDefinition map);

    /**
     * 摘除注册式地图;文件式地图(id 来自 {@code maps/*.yml})→ 响亮拒绝(20-D4)。
     * 该图为任一存活会话当前实例所用 → 摘除被拒(detail 点名会话——图随会话打完
     * 自然退出,或 dissolve 后再摘;这是特性不是障碍,20-D4)。幂等:已不在目录 =
     * no-op。提供方插件 disable → 框架兜底摘除(正用则保留并 WARN)。主线程调用。
     */
    void unregisterMap(org.bukkit.plugin.Plugin provider, String mapId);

    // ── 目录(顺序 = 注册序/装载序,不排序不过滤——14.21「大厅特判」反弹规避) ──
    //    第三方模式经 MatchModeProvider 注册(7.2,ServicesManager 收录)入目录后
    //    服主经本组方法可见,与其他模式同列、无特判(§7.2/§13.4;框架不设出厂模式,21-D3)。

    /** 全部已注册模式快照(注册序,含第三方模式;空表合法(0 个模式属正常))。主线程调用。 */
    List<MatchModeInfo> modes();

    /** 全部已装载地图快照(文件装载序 + 注册序;空表合法)。主线程调用。 */
    List<MapInfo> maps();

    /** 按模式 id 取快照;缺失 → empty(不抛)。主线程调用。 */
    Optional<MatchModeInfo> mode(String modeId);
}
