## 7. 对外公共 API:`MiniGameApi`(v2 新增,v3 修订注册面)

> HydroKarbs MiniGame Framework · v1.3(现行;v1.2 于 2026-10-23 地图注册通道;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充;v1.0 于 2026-10-22 自 SHDFMatch 设计案 v4.1 剥离玩法重构;玩法实现见 Shdf具体玩法设计案/)
>
> 文档版本:v1.3(2026-10-25,框架基础设施瘦身——「查战绩」面随 R1 悬置(§7.1/§7.5 注;API_VERSION 维持 3,方法集不变),白名单反面词表历史口径注(§7.2),21-D1;v1.2 于 2026-10-23 地图注册通道——§7.1 增 registerMap/unregisterMap、API_VERSION=3、§7.5 权限面加行;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充——§7.1 升 v2、§7.2 加三行、§7.3 SPI 未决项由 D14 裁决关闭、新增 §7.5 跨插件消费;v4.1 于 2026-10-21 进程级服务层与 ServiceRegistry;会话级组件层——两级挂载;新增 §7.3、§7.4)。

框架化的最后一块拼图:本框架**自己也要作为一个平台**被别的插件消费——与它消费任一宿主服务的 ServicesManager 是同一机制。同一套纪律,换个方向(框架自身消费宿主服务的实例属 SHDF 扩展线,见 Shdf具体玩法设计案/)。

### 7.1 接口(小而深;方法集冻结;★v1.1:v2 增量,只增不改;★v1.2:v3 增量)

```java
package com.hydrokarbs.api;

/** 本框架的对外公共面。经 Bukkit ServicesManager 暴露。
 *  ★v1.1(D7):API_VERSION 版本协商——只增不减;消费方 onEnable 首行检查,
 *  低于要求 → SEVERE + setEnabled(false)(拒载不崩服,14.36)。v1.0 = 1;v1.1 = 2;v1.2 = 3。 */
public interface MiniGameApi {

    int API_VERSION = 3;

    // ── 查询 ─────────────────────────────────────────────────
    Optional<SessionView> sessionOf(UUID player);
    Optional<SessionView> session(String sessionId);
    List<SessionView> sessions();

    // ── 控制(与 /sm 命令同权) ────────────────────────────────
    /** 切换某会话的模式。就是 SessionService#switchMode 的受控暴露(§3.2)。 */
    CompletableFuture<SwitchResult> switchMode(String sessionId, MatchModeSelection target, Handoff payload);
    /** 创建会话(arena 拓扑下第三方可开私设竞技场)。既有两参重载保留,等价于空 recipe。 */
    SessionView createSession(String initialModeId, String initialMapId);
    /** ★v1.1-D14 通道二:会话配方(单次贡献会话组件),见 §7.5。 */
    SessionView createSession(SessionRecipe recipe);

    // ── 成员操作(★v1.1-D2;★42) ────────────────────────────
    /** 将玩家加入会话(= SessionService#join 的受控暴露,§3.2;含 JoinAdmission 裁决)。主线程调用。 */
    JoinResult joinSession(String sessionId, UUID player);
    /** 将玩家移出其当前会话;不在任何会话 = no-op(幂等)。主线程调用。 */
    void leaveSession(UUID player, LeaveReason reason);

    // ── 会话事件订阅(★v1.1-D3;★43) ─────────────────────────
    /** 订阅会话事件(七事件族,§3.1)。返回句柄,close() 注销;订阅者异常被隔离(镜像总线既有隔离)。 */
    EventSubscription subscribeSessionEvents(Consumer<SessionEventView> handler);

    // ── 能力标签(★v1.1-D4) ─────────────────────────────────
    /** 注册能力标签(提供方 = 本插件,如角色/经济适配)。幂等;提供方插件 disable 时自动摘除。 */
    void registerCapability(org.bukkit.plugin.Plugin provider, String tag);
    Set<String> capabilities();

    // ── 地图注册(★v1.2;★54) ──────────────────────────────
    /** 注册地图(声明 = 纯静态 MapDefinition,直接交成品——与模式必须交工厂相对,20-D1)。
     *  注册期校验:id 查重(与文件式同一命名空间,重复点名先到者)、tags、世界模板目录存在性
     *  (maps/templates/<mapId>/,缺失响亮抛)。成功返回 MapInfo 快照。主线程调用。 */
    MapInfo registerMap(org.bukkit.plugin.Plugin provider, MapDefinition map);
    /** 摘除注册式地图;文件式地图(id 来自 maps/*.yml)→ 拒绝。该图为任一存活会话当前实例所用、
     *  或存在未释放世界句柄 → 摘除被拒(detail 点名会话)。幂等:已不在目录 = no-op。 */
    void unregisterMap(org.bukkit.plugin.Plugin provider, String mapId);

    // ── 目录 ─────────────────────────────────────────────────
    List<MatchModeInfo> modes();
    List<MapInfo> maps();
    Optional<MatchModeInfo> mode(String modeId);

    // ── 战绩 ─────────────────────────────────────────────────
    // ★v1.3(21-D1/R1):本族随持久化出框架而【悬置】——归宿主线消费;框架冻结面 7.1 落地时
    //   不含 DB 依赖项(两方法暂不实现;签名类型 PlayerStats/LeaderboardRow 原住 L5 data/model,
    //   已随 L5 出框架)。API 方法集冻结「只增不改」,本注不改签名;缺席语义与类型归宿的回填
    //   裁决登记 21 §6-Q1,待宿主线持久化落定后回填。
    CompletableFuture<Optional<PlayerStats>> stats(UUID player, String modeId);
    CompletableFuture<List<LeaderboardRow>> top(String modeId, String column, int limit);
}
```

```java
package com.hydrokarbs.api;

// api/view/SessionEventView —— ★v1.1-D3:不可变快照族(七事件各一 record;字段口径 = §3.1 七事件载荷:
// from/to、reason、时间戳;不含活会话/活组件引用——镜像 ★29 现取不缓存纪律)
public sealed interface SessionEventView {
    record Created(SessionId session, long tick) implements SessionEventView {}
    record MemberJoined(SessionId session, UUID player, long tick) implements SessionEventView {}
    record MemberLeft(SessionId session, UUID player, String reason, long tick) implements SessionEventView {}
    record MemberReconnected(SessionId session, UUID player, long tick) implements SessionEventView {}
    record ModeSwitchStarted(SessionId session, String from, String to, long tick) implements SessionEventView {}
    record ModeSwitchCompleted(SessionId session, String from, String to, boolean ok, long tick) implements SessionEventView {}
    record Dissolved(SessionId session, String reason, long tick) implements SessionEventView {}
    record ComponentQuarantined(SessionId session, String componentId, String cause, long tick) implements SessionEventView {}
}

// api/EventSubscription —— ★v1.1-D3:订阅句柄(close() 注销;句柄只在插件实例字段中持有,onDisable 显式 close,14.38)
public interface EventSubscription extends AutoCloseable { @Override void close(); }

// api/SessionComponentProvider —— ★v1.1-D14 通道一:全局默认贡献(插件可"拼"会话,模式仍不可)
public interface SessionComponentProvider {
    List<SessionComponent.Specification<?>> sessionComponents();
    default int requiredApiVersion() { return 1; }
}
// 注册:getServer().getServicesManager().register(SessionComponentProvider.class, provider, this, Normal)
// 收录:ServiceRegisterEvent → 干跑合并校验(查重/依赖)→ 坏则 SEVERE 拒收录;此后所有 createSession 合并

// api/SessionRecipe —— ★v1.1-D14 通道二:单次贡献(既有两参 createSession 保留,等价于空 recipe)
public record SessionRecipe(String initialModeId, Optional<String> initialMapId,
                            List<String> mapPool,
                            List<SessionComponent.Specification<?>> extraComponents) {
    public static SessionRecipe of(String modeId, String mapId) { /* mapPool/extra 空表 */ }
}
```

> ★v1.1-D3 边界:**实例事件(`MatchModeInstanceEvent`)第一版不经此面暴露**——玩法侧组件经自身实例总线已有全量;跨插件订阅实例事件登记为未决(19-§7-Q5,看板类需求先验证会话快照 + D3 是否够用),不裁。顺序承诺:单会话内事件有序;跨插件订阅者间不承诺送达顺序。`MiniGameApiImpl` 增量实现均为薄委托:`joinSession/leaveSession → SessionService`;`subscribeSessionEvents → 各 Session.events() 聚合 + 会话注册表遍历挂接(新会话创建时自动接入)`;`registerCapability → CapabilityRegistry`。

### 7.2 注册、冻结与扩展点

| 事项 | 做法 |
|---|---|
| 注册 | `onEnable`:`getServer().getServicesManager().register(MiniGameApi.class, impl, this, ServicePriority.Normal)`——与消费任一宿主服务的 ServicesManager 同一机制,对称 |
| 冻结 | `MiniGameApiSurfaceTest` 反射冻结方法集(同款"服务面冻结测试"在 SHDF 扩展线的先例 `RoleApiSurfaceTest`,见 Shdf具体玩法设计案/):只增不改,删/改签名即测试红 |
| 值对象 | `SessionView` / `MatchModeInfo` / `MapInfo` 全是不可变 record,不泄漏引擎内部可变状态(`SessionView` 是快照,不含活实例引用) |
| **第三方注册模式** ★v3 | 注册面是 **`MatchModeProvider`**(`{ String id(); MatchMode build(MatchModeConfig cfg); }`)——聚合根是 final 类不是接口,Builder 即声明语言,工厂接口即 SPI。`MatchModeRegistry` 双发现:①`ServiceLoader`(本 jar 内,`META-INF/services/com.hydrokarbs.engine.mode.MatchModeProvider`——★v1.1-D8 注:只覆盖同 jar,跨 jar 注册走②);②`ServicesManager`——第三方插件 `register(MatchModeProvider.class, provider, ...)`,我们监听 `ServiceRegisterEvent` 收录并 `build()`(其内部自带 `verifyDependencies`,坏模板不进注册表;同款"装配即校验"纪律的先例 `RoleLoader#loadInto` 属 SHDF 扩展线,见 Shdf具体玩法设计案/)。**别的插件不改我们的 jar 就能加模式**,这是"框架"二字成立的验收标准 |
| 第三方组件 | 不开放。第三方模式复用我们已发布的 L3 组件(`MatchModeComponent` 基类与 `Specification` 在 api 包导出稳定副本)+ 模式包内自建私有组件 |
| **API 编译面白名单** ★v1.1(D6) | **不移动引擎类型进 api/**(避免 engine→api 依赖倒挂),改为白名单冻结:玩法/伴随插件 compileOnly 依赖框架 jar 时允许 import 的包集 = `api/**`、`engine.mode/**`、两级组件基类(`MatchModeComponent`/`SessionComponent` + Specification/Factory,不含 Registry)、`engine.session` 值类型(Handoff/EndResult/TransitionIntent/MatchModeSelection/SwitchResult/JoinResult/LeaveReason/SessionId/DissolveReason)、`engine.port` 契约、`engine.event/**`、`component/**` 各包公开数据件与事件类型、`map/` 读面(逐包清单落 7.5 施工文档)。**机械守卫**:`ApiSurfaceTest` 双断言(★44)——①`MiniGameApi` 全部方法签名只引用白名单类型;②白名单内类型的公开签名**只增不改**(反射快照)。**永不导出**(白名单反面,断言显式列出):`SessionService(Impl)`、`EngineTicker`、`WorldPort`、`ServiceRegistry` 族、`ComponentRegistry` 族、`bridge/**`、各 Impl(★v1.3:原反面词 `PersistencePort`/`data/**` 为历史口径——持久化已整体出框架基线,该包不存在、断言空转不减,21-D1)。跨 jar 场景下进程内全权访问特权仅保留给形态 A 的宿主 L0 装配/监听/命令(彩排纪律:形态 A 下宿主代码也应只用白名单 + L0 装配点);玩法插件侧自建 ArchUnit 模板随 7.5 施工文档提供(框架无法跨 jar 强制,review 条款兜底) |
| **版本协商** ★v1.1(D7) | `MiniGameApi.API_VERSION`(v1.0 = 1,v1.1 = 2,v1.2 = 3,只增不减);玩法/伴随插件 `onEnable` 首行检查 `api.API_VERSION < REQUIRED_API_VERSION` → SEVERE + `setEnabled(false)`(拒载不崩服);框架 `onEnable` 日志输出 API_VERSION,对注册的每个 Provider 记录其声明的要求版本(`MatchModeProvider` 增 `default int requiredApiVersion() { return 1; }`,只增默认方法不破既有实现);兼容纪律:白名单内公开签名只增不改,破坏性变更必须升 API_VERSION 并提供**至多一个次要版本**的双轨过渡期(One-Version Rule,不允许长期双轨) |
| **禁 shade** ★v1.1(D7/14.35) | 玩法/伴随插件 **compileOnly** 依赖框架 jar + 运行期 `depend: [HydroKarbs]`;构建脚本断言消费方 jar 内不得出现 `com.hydrokarbs.` 前缀类(消费方各用自有包根,宿主 = `com.shdf.*`)——shade 框架类会导致双引擎/ClassCastException/模式注册进错误注册表 |

> 为什么把模式注册放行给第三方、而组件不放行:模式是**声明级**扩展(Builder 拼数据,风险面小——坏声明在 `verifyDependencies`/`checkMap` 就被拒);组件会挂进我们的 tick 循环,坏组件能拖垮 TPS。先开放前者,后者等有真实需求再加(Assumption-driven,不做 hypothetical seam)。
> **v3 补充的刻意限制(A9)**:聚合根 `build()` 后不可变,第三方**不能**给已注册模式追加组件或改组件表——要"变体"就注册一个新 id。这杜绝了运行期装饰带来的加载顺序地狱。

### 7.3 会话级组件的 API 边界(★v4.0 增补;★v1.1 修订:SPI 未决项由 D14 裁决受限开放,查询面未决维持)

v4.0 引入**会话级组件**(`SessionComponent`:每会话一份、跨模式切换存活、随 `dissolve` 全灭;生命周期与跨级查询见 §3.8,契约骨架见 §12.2)。它住在引擎内部,对外公共面在 v4.0 的边界结论:

| 事项 | v4.0 边界 |
|---|---|
| `MiniGameApi` 冻结面 | **第一版不进 `MiniGameApi` 冻结面**:不新增任何会话级组件相关的方法;装配来源、派发节拍、故障隔离都由引擎/L0 持有——会话级组件不是对外契约,§7.1 方法集与 §7.2 的冻结纪律原样有效(**★v1.1 修订**:本行"不新增会话级组件相关方法"由 19-D14 受限突破——新增的是 `createSession(SessionRecipe)` 重载与 `SessionComponentProvider` 注册面,仍走"只增不改") |
| `SessionView` 同名消歧 | **两个 `SessionView` 同名不同义**:`api/view/SessionView`(不可变 record 快照,§7.1/§7.2 冻结面)与 `engine/port/SessionView`(★v4.0 实例侧只读会话视图**接口**,§12.2)——公共面只暴露前者,引擎契约不属 `api/`;两者不得混用。★v1.1:api/ 新增的 `SessionEventView` 是第三种视图(会话事件快照族),与两者均不同义 |
| 第三方会话组件 SPI | **已裁决关闭(★v1.1 修正案 19-D14,修订原"第一版不开放")**:**受限开放**——插件(宿主/伴随)经两条通道贡献会话组件:①全局默认 `SessionComponentProvider`(经 ServicesManager 注册,注册期干跑合并校验,坏 provider 拒收录、会话照常);②单次 `createSession(SessionRecipe)` 携带 `extraComponents`(查重/缺依赖 → 响亮失败)。合并序 = L0 拓扑表 → provider(注册序)→ recipe;**模式仍无权贡献(v4-D4 不变)、无运行期增删**(贡献只发生在会话创建时,装配点唯一不变);防线 = 注册期干跑校验 + 装配期 verifyDependencies + 运行期 quarantine(★30)+ 预算告警(D9-③ 扩至会话组件)。原未决项(17 §11-Q2)就此关闭;**第三方查询会话组件实例维持不开放**(下行) |
| 第三方查询会话组件 | **未决维持**:第三方经 API 查询会话组件实例——第一版明确不进;日后若开放,仍走 §7.2"只增不改"的冻结纪律(19-§7 未决项随 D14 登记口径) |
| 实例组件订阅会话事件 | **未决(变更指示 §11-Q7;19-§7-Q2 维持不开放)**:第一版**不开放**——`SessionView` 不提供 `events()`(实例组件不得订阅会话事件,§3.8/§12.2);登记去向:本节与 11(§11.4 注),待裁决 |
| `SessionService` 公开方法签名 | **不变**:v4.0 只在其内部扩展**装配/派发/回收接线**(装配在 `create` 内、先于首个实例;派发在 `tickAll` ③ 步;回收在 `dissolve` 逆序 `stop` + 登记资源回收)——**装配/派发/回收接线扩展不改变公共面**:公开方法签名不变(§3.2),`switchMode` 的受控暴露(§7.1)与实现类的薄委托关系(§7.2)都不受影响 |

> 一句话:v4.0 的插拔发生在**引擎内部**;公共 API 的消费者(别的插件)在 v4.0 里既不能查询会话组件,也不能提供会话组件。**★v1.1 裁决(D14)**:后半句已突破——插件(宿主/伴随)可经 `SessionComponentProvider`(全局默认)与 `SessionRecipe`(单次)两条通道**提供**会话组件(模式仍不可,无运行期增删);**查询**会话组件实例维持不开放(未决)。

### 7.4 进程级服务的 API 边界(★v4.1 增补)

v4.1 引入**进程级服务**(Service:进程生命周期,`onEnable` 装配 → `onDisable` 关停,经 `ServiceRegistry` 两阶段装配,不随任何会话/实例生灭;概念定义与硬边界见 §2.9,服务 ↔ 会话的三通道见 §3.9,契约骨架见 §12.2)。与会话级组件一样,它住在引擎内部,对外公共面在 v4.1 的边界结论:

| 事项 | v4.1 边界 |
|---|---|
| `MiniGameApi` 冻结面 | **第一版不进 `MiniGameApi` 冻结面**:服务、`ServiceRegistry`、服务查询一律**不进公共面**(修正案 D9)——不新增任何方法;§7.1 方法集与 §7.2 的冻结纪律原样有效,`MiniGameApiSurfaceTest` 现有断言即守卫 |
| 第三方消费服务 | **未决(修正案 §12-Q9)**:`MiniGameApi` 是否暴露服务查询/进程级快照——第一版明确**不进**;登记去向:本节(07 §7.4);日后若开放,仍走 §7.2"只增不改"的冻结纪律 |
| 第三方提供服务 | **未决(修正案 §12-Q9;E 案另见 §12-Q8)**:第三方提供进程级基础设施的版本化 SPI 是否开放、何时——第一版**不开放**;修正案对 §12-Q8 的建议去向为 13 选型节与 ADR-0006(`docs/adr/0006-process-service-registry.md`;修正案原文编号 0005,因 `docs/adr/0005` 已被《架构冻结测试门禁》占用而顺延为 0006)的未决项;本条登记去向:本节(07 §7.4) |
| `SessionService` 公开方法签名 | **不变**:v4.1 不在 `SessionService` 公开面新增任何服务相关方法;服务触达会话走三通道(订阅 `SessionEventBus`、只读快照、受控命令)——受控命令即 `switchMode` / `dissolve` / `createSession` 既有签名(§3.2/§7.1),服务属切换来源②,与 `/sm` 命令同权 |

> 一句话:v4.1 的进程级服务同样是**引擎内部居民**——公共 API 的消费者(别的插件)既查询不到服务,也(第一版)提供不了服务;服务触达会话走三通道,不经 `MiniGameApi`。开放与否留给未决问题(§12-Q9、§12-Q8)裁决。
>
> ★v1.1 注(D10):三通道与 API 面扩充**互不打通**的声明为本节正式口径——服务是引擎内部居民,不消费 `MiniGameApi`;`joinSession`/`leaveSession` 扩大的是**外部调用方**阵营(命令/监听/伴随插件/玩法插件)的执行权,不构成第四通道(ArchUnit 增款:`engine/service/**` 与顶层 `service/**` 不得 import `api/**`,★40 增款;14.37)。本节两个未决项(§12-Q9/Q8)**维持**,不随 v1.1 改变。

### 7.5 跨插件消费(★v1.1:伴随插件的完整权限面)

v1.1 起,框架是**独立插件**、玩法与伴随插件是**消费方**(`depend: [HydroKarbs]`,compileOnly + 禁 shade,14.35)。扩充后的 `MiniGameApi` 给伴随插件(13 §13.9 案②形态)完整权限面——与玩法插件同权,不再是"只能看":

| 能力 | API 面 | 说明 |
|---|---|---|
| 查询 | `sessionOf` / `session` / `sessions` / `modes` / `maps` / `stats` / `top`(★v1.3:`stats`/`top` 随 R1 悬置——归宿主线消费,框架冻结面落地时不含 DB 依赖项,回填裁决 21 §6-Q1) | 不可变 record 快照;不持活引用跨切换(现取不缓存,14.31 同款纪律) |
| 控制 | `switchMode` / `createSession`(两参与 `SessionRecipe` 重载) | 与 `/sm` 命令同权;`force` 是显式选择,走 review 条款 |
| 成员 | `joinSession` / `leaveSession`(D2) | = `SessionService#join/leave` 的受控暴露;含 `JoinAdmission` 裁决;leave 幂等;权限/审计口径未决(19-§7-Q3,倾向第一版无权限闸 + SEVERE 审计日志) |
| 事件 | `subscribeSessionEvents`(D3) | `SessionEventView` 不可变快照族(七事件);句柄 `close()` 注销;订阅者异常隔离;实例事件不暴露(Q5) |
| 能力标签 | `registerCapability(Plugin, String)` / `capabilities()`(D4) | 提供方 = 本插件;幂等;插件 disable 自动摘除(接通 `ServiceUnregisterEvent`,§8 先例 14.7 懒解析与动态刷新纪律沿用) |
| 会话定制 | `SessionComponentProvider`(通道一)/ `SessionRecipe`(通道二)(D14) | 插件可"拼"会话;模式仍无权;无运行期增删;合并序与防线见 §7.3 |
| 地图注册 | `registerMap(Plugin, MapDefinition)` / `unregisterMap(Plugin, String)`(★v1.2) | 文件装载之外的第二输入通道(同一 `MapRegistry` 命名空间);**地图交成品不交工厂**(纯静态值);注册期校验 id/tags/模板目录;正用摘除被拒;`WorldPort` 不受影响(20-D1~D7;步骤见 §13.11) |
| 版本 | `API_VERSION`(D7) | `onEnable` 首行检查,低于要求拒载不崩服(14.36) |

**信任边界(D9 三层)**:①装配期拒坏声明(`verifyDependencies`/`checkMap`/注册期 build 校验、provider 干跑合并校验);②运行期隔离(实例组件异常 → quarantine → `end()` → 回退;会话组件/贡献组件异常 → 隔离不解散,★30/★48/★53);③预算观测(`EngineTicker` 对组件 `update` 计时,单组件单 tick 超阈值记 WARNING——会话 id/组件 id/提供方插件;硬熔断未决 Q4)。

**示例一:入口牌子插件**(hub 的牌子/NPC → 编排玩家 hub→arena):

```java
public class JoinSignPlugin extends JavaPlugin {
    private EventSubscription sub;                       // 句柄只在实例字段(14.38)
    private MiniGameApi api;

    @Override public void onEnable() {
        api = getServer().getServicesManager().load(MiniGameApi.class);
        if (api == null || api.API_VERSION < 2) {        // ★v1.1-D7:拒载不崩服(14.36)
            getLogger().severe("HydroKarbs API_VERSION 过低或缺席,禁用"); setEnabled(false); return;
        }
        sub = api.subscribeSessionEvents(ev -> {          // D3:会话事件快照
            if (ev instanceof SessionEventView.Dissolved d) updateSigns(d.session());
        });
    }
    void onSignClick(Player player, String arenaId) {     // 主线程
        api.leaveSession(player.getUniqueId(), LeaveReason.MOVED_OUT);   // D2:hub 出局(幂等)
        api.joinSession(arenaId, player.getUniqueId())                    // D2:arena 入局(含 JoinAdmission 裁决)
           .ifRejected(reason -> player.sendMessage(Text.warn("入场被拒:" + reason)));
    }
    @Override public void onDisable() { if (sub != null) sub.close(); }   // 显式注销(14.38)
}
```

**示例二:跨服看板插件**——`subscribeSessionEvents`(Created/Dissolved/ModeSwitchCompleted)+ `sessions()` 只读快照维护"现存会话"视图;数据一律现取,不持活引用(镜像服务层 `GlobalBoard` 形态,§13.9 案①)。

**示例三:赛程器/第二宿主**——`createSession(new SessionRecipe("mode@map", pool, List.of(new MyGraceComponent.Spec(...))))` 按需定制竞技场(D14 通道二);宿主级策略走 `SessionComponentProvider` 注册(通道一)。详见 §13.10。

---
