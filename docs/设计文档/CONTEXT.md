# CONTEXT.md — 领域术语表(HydroKarbs MiniGame Framework)

> HydroKarbs MiniGame Framework · v1.3(现行;v1.2 于 2026-10-23 地图注册通道;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充;v1.0 于 2026-10-22 自 SHDFMatch 设计案 v4.1 剥离玩法重构;玩法实现见 Shdf具体玩法设计案/)
> 本文件只放术语与定义,不放实现细节。源自上游 v4.1 设计案(同源复制后按 v1.0 剥离玩法重构,上游代号见上方版本行与本表「代号」节;v1.1 修正案见 19/ADR-0007;v1.3 修正案见 21/ADR-0009);本文件夹的 README 有最终解释权。SHDF 扩展线术语见节题框注。

## 代号

| 术语 | 定义 |
|---|---|
| **HydroKarbs MiniGame Framework(简称 **HKF**;v1.0 / v1.1 / v1.2 / v1.3)** | 本术语表所属框架的代号:自 SHDFMatch 设计案 v4.1 剥离全部具体玩法重构而成的纯框架(v1.0);v1.1(2026-10-23,框架独立插件化拆分与 API 面扩充,见 19 与 docs/adr/0007);v1.2(2026-10-23,地图注册通道,见 20 与 docs/adr/0008);当前版本 **v1.3(2026-10-25,框架基础设施瘦身与配置中立化,见 21 与 docs/adr/0009:持久化与表现层出框架、发行配置零玩法缺省、启动建会话特性删除与会话创建权移交宿主)**。行文简称 **HKF**(HydroKarbs MiniGame Framework 的缩写;插件名 `HydroKarbs`、类名 `HydroKarbsPlugin`/`HydroKarbsAssembly` 与包根 `com.hydrokarbs` 是标识符,不是简称,不随本条变化)。装配类名相应为 HydroKarbsAssembly / HydroKarbsPlugin,框架 Java 包根为 `com.hydrokarbs.*`(D13,旧根 `com.shdf.match` 废止)。具体玩法(大厅/四模式)与角色系统接入(SHDF 宿主扩展)的实现见 Shdf具体玩法设计案/;SHDFMatch 为历史代号,存档于 17/18 与 docs/adr/,不改。 |

## 交付形态(v1.1)

| 术语 | 定义 |
|---|---|
| **框架插件(Framework Plugin)** | 独立插件 `HydroKarbs`:L2 引擎 + api/ + L1 通用桥(config/world)+ L3 通用组件 + 通用 listener/command。一个 Bukkit 插件、一个主类 `com.hydrokarbs.HydroKarbsPlugin`(机制上仍恰有一个主类,12 §12.1 不变);形态 B 产物边界见 12 §12.1 追加块。(**★v1.3**:原「L1 通用桥含 persistence / L5 数据 / render」已出框架——`bridge/persistence`、`data/**`、`render/**`、`component/display/**` 不在框架基线,21-D1/D2;持久化归宿主线自建,表现层见 Shdf具体玩法设计案/10)。 |
| **玩法插件(Gameplay Plugin / 宿主插件)** | 消费框架的插件(如 SHDF):`MatchModeProvider` 模式工厂、私有组件、宿主桥接(如 `bridge/role`)、玩法命令与监听;`plugin.yml: depend: [HydroKarbs]`;独立顶级包根(宿主 = `com.shdf.*`,旧根 `com.shdf.match` 不复用,D13)。 |
| **伴随插件(Companion Plugin)** | 消费框架的第三方插件(13 §13.9 案②的既有形态):只消费 `MiniGameApi` 公共面——完整权限面见 07 §7.5(查询+控制+成员+事件+能力+会话定制);v1.1 起经扩充后的 `MiniGameApi` 拥有完整执行权(19-§1 术语表)。 |
| **API 编译面(API Surface)** | 玩法/伴随插件 compileOnly 依赖框架 jar 时**允许 import 的白名单包集**(19-D6;清单落 07 §7.2 与 7.5 施工文档);由 `ApiSurfaceTest` 双断言冻结(★44:MiniGameApi 签名零非白名单类型 + 白名单公开签名快照只增不改),与 `MiniGameApiSurfaceTest`(★19)互补。永不导出:`SessionService(Impl)`、`EngineTicker`、`WorldPort`、`ServiceRegistry` 族、`ComponentRegistry` 族、`bridge/**`、各 Impl。(**★v1.3**:原反面词表中的 `PersistencePort` 与 `data/**` 为历史口径——持久化已整体出框架基线,该包不存在,21-D1。) |
| **API_VERSION** | 框架 API 面的版本常量(19-D7):v1.0 = 1,v1.1 = 2,v1.2 = 3,只增不减;玩法/伴随插件 `onEnable` 首行检查,低于要求 → SEVERE + `setEnabled(false)`(拒载不崩服);破坏性变更必须升版本并至多一个次要版本双轨过渡(One-Version Rule)。(**★v1.3**:v1.3 不改任何 API 方法,API_VERSION **维持 3**;07 查战绩面随 R1 悬置,回填裁决见 21 §6-Q1。) |
| **双形态交付(G1/G2)** | 形态 A(单 jar,现状:框架+宿主同 jar;宿主代码按白名单纪律写,仅 L0 装配/监听/命令三处豁免)与形态 B(双 jar 物理拆分);同一套源码与同一验收集,形态 B 是超集交付、不是分叉。两道闸门:**G1** = API 面扩充并在形态 A 下彩排全绿(★41~★46/★51~★55,含 ★v1.2 地图注册);**G2** = G1 全绿**且**首个真实跨 jar 消费者出现(A13)后执行物理拆分(★47~★50;★50 断言双形态等价)(19-D11)。 |
| **SessionRecipe(会话配方)** | ★v1.1-D14 通道二:`createSession(SessionRecipe)` 重载携带 `extraComponents`(单次贡献会话组件);与 L0 拓扑表、provider 贡献合并后统一 `verifyDependencies`,查重/缺依赖 → `createSession` 响亮失败。既有两参 `createSession` 保留,等价于空 recipe;`engine.session.SessionSpec` 保持内部(recipe→spec 由框架转换,外部不见)。(**★v1.3-D5**:「L0 拓扑表」废止——`SessionSpec` 由宿主构造传入 `create`,合并序 = 宿主组装的 spec.sessionComponents → provider → recipe;合并机制与冻结面签名不变,`initialModeId` 仍由调用方填。) |
| **SessionComponentProvider(会话组件贡献者)** | ★v1.1-D14 通道一:插件(宿主/伴随)经 ServicesManager 注册的全局默认贡献;框架 `ServiceRegisterEvent` 收录时**干跑合并校验**(查重/依赖),坏 provider 拒收录(SEVERE 点名)、会话照常;收录后所有后续会话装配(含拓扑启动装配)合并其贡献;provider 插件 disable 后自后续装配摘除(已装配会话不受影响)。合并序 = L0 拓扑表 → 各 provider(注册序)→ recipe.extra;模式仍无权贡献(v4-D4 不变),无运行期增删。(**★v1.3-D5**:「拓扑启动装配」随启动建会话特性废止——框架 onEnable 零会话,贡献合并发生在**宿主每次调 `create`** 的装配期;合并序 = 宿主组装的 spec.sessionComponents → 各 provider → recipe.extra,机制不变。) |

## 会话层

| 术语 | 定义 |
|---|---|
| **Session(会话)** | 跨模式常驻的玩家容器。★v4:职责 = **内核机制 + 会话级组件**——名册、连接追踪、状态机、切换、世界机制仍为内核机制并保持权威,策略与行为可下放为会话级组件。进程内概念,不落库。 |
| **SessionMember(会话成员)** | 会话级成员身份:uuid、连接态、加入时间。刻意不含 team/role/state——那些是实例概念。 |
| **SessionRoster(名册)** | 一个会话全体 SessionMember 的登记簿。模式切换时名册**原地位移**,不搬数据。 |
| **SessionService** | 框架内负责会话生命周期与模式切换的内核服务。`switchMode` 是它的一等方法。 |
| **Dissolve(解散)** | 会话的终结:成员传回主城、释放世界、从注册表摘除。与"实例结束"是两回事。 |
| **拓扑(Topology)** | 服务器部署会话的方式:rotation(单会话轮换)或 arena(多竞技场并行)。引擎不感知。(**★v1.3-D5**:不再是框架配置概念——`session.topology` 键删除、`HydroKarbsAssembly` 拓扑装配废止,**拓扑 = 宿主调几次 `SessionService.create(SessionSpec)` 的次数与参数**(会话创建权移交宿主);引擎零感知口径不变,零会话起服为合法状态。) |
| **SessionComponent(会话级组件)** | ★v4:与实例级 MatchModeComponent 并列的第二挂载级,会话级行为单元基类。**每会话一份**、跨模式切换存活,随 dissolve 全灭;会话的策略与行为可下放于此(机制仍在内核)。 |
| **SessionServices(svc)** | ★v4:会话组件的服务集 record,两成员封闭:(self, components)。构造期一次性注入,纪律与实例级 MatchModeInstanceServices 一致。 |
| **SessionPort(self)** | ★v4:会话自面,会话级端口的落点:id/members/tick/attrs/events/currentInstance/later + **requestSwitch(target, payload)** / **requestDissolve(reason)**(会话级出局口,镜像实例级的 switchTo()/end())。 |
| **MatchModeInstanceAccess(实例访问面)** | ★v4:会话侧的**只读**实例访问面(无写口):实例信息、参赛者、实例组件的只读查询;无实例或切换间隙为空,**不得缓存实例组件引用越过切换**。 |
| **SessionView(会话视图)** | ★v4:实例侧的**只读**会话视图(经 MatchModeInstancePort 的 session() 取得):会话信息与**会话组件的只读查询**——模式接入会话级能力的通道。 |

## 模式声明层(v3 重构核心;v3.1 加 Match 前缀)

| 术语 | 定义 |
|---|---|
| **MatchMode(模式聚合根)** | 模式的**纯数据聚合**:id/名称/描述/图标/mapTags/MatchModeMeta + 唯一有序组件描述符表。★ **零行为**——仅有的两个方法是装配期的 `verifyDependencies()` 与 `createInstance()`。Builder 产出,build 后不可变。 |
| **MatchModeMeta** | 模式元数据 record:requiredCapabilities(字符串能力标签)/recordsHistory/人数窗/defaultTransition。全是数据;v3.1 起无预设常量,各模式工厂逐字段自建。★v3.2:joinPolicy 字段已删(下放为 JoinControlComponent,§3.2.1)。 |
| **Capability(能力标签)** | 外部插件能力标签(字符串)。MatchModeMeta.requiredCapabilities 声明,开局期校验;所需标签缺席时,声明它的模式被拒、其余模式照常。引擎不认识任何具体标签(v3.1);机制为框架口径,具体标签与注册方为 SHDF 扩展线词汇(见 Shdf具体玩法设计案/)。 |
| **MatchModeComponent.Specification(装配描述符)** | 声明"这个组件怎么造":工厂、requires/requiresOptional 依赖类型、providedType(供给面)、checkMap(地图适配)。freeze() 产出不可变快照,同一 Spec 跨模式共享安全。 |
| **ComponentEntry** | 描述符表条目:(工厂, 描述符类型, 提供类型, 必需依赖, 可选依赖) 的快照。不持有 Spec 对象。 |
| **MatchModeProvider** | SPI 工厂接口 `{ id(); MatchMode build(cfg); }`。聚合根是 final 类,第三方经它注册模式;build 后不可追加组件(A9)。 |
| **Lobby(大厅)** | 示例模式(SHDF 提供):等人、投票、倒计时;用 Builder 声明的普通聚合根,零特权、可整体替换。(SHDF 扩展线示例,实现见 Shdf具体玩法设计案/04-大厅模式) |

## 实例运行层

| 术语 | 定义 |
|---|---|
| **MatchModeInstance(模式实例)** | 一次模式运行(俗称"一局")。**容器**:按描述符表造组件、按声明序派发、提供端口、回收登记资源。不持有玩法状态(「状态唯一」)。 |
| **MatchModeComponent(模式组件)** | 行为单元基类:id + svc(构造期注入),生命周期 awake/start/update/onEvent/onHandoff/stop。所有玩法状态住在组件里,组件是状态唯一主人并提供强类型读口。 |
| **MatchModeInstanceServices(svc)** | 组件的服务集 record,三成员封闭:(self, components, modeInfo)。照抄角色系统 ComponentServicesPort 纪律;不为任何领域增设转发成员。 |
| **MatchModeInstancePort(self)** | 实例自面,框架「基础端口」的落点:participants/tick/attrs/handoff/map/cfg/events/later + **end(EndResult)** / **switchTo(target, payload)** + ★v4 新增 **session()**(所属会话的只读视图 **SessionView**)。 |
| **Participant(参赛者)** | 成员在某次实例内的视图:uuid、生命周期标记、实例 attrs。team/role 归组件所有,经组件读口查询。 |
| **状态唯一** | 纪律:玩法状态只住在一个组件里,容器与其他组件不得复制持有;跨组件读写走状态主人的读口。 |
| **JoinControlComponent(入场控制组件)** | ★v3.2:决定会话成员如何成为当前实例的参赛者/观战或被拒(ADMIT/SPECTATE/REJECT)。实现引擎端口 `JoinAdmission`,在「切换 B4 初始摆放」与「运行中 join」两个触发点被 SessionService 查询(组件声明序第一个实现者);**不挂 = 默认全接纳**。原 MatchModeMeta.joinPolicy 枚举的下放(§3.2.1)。 |

## 会话级组件(v4)

| 术语 | 定义 |
|---|---|
| **会话级组件(SessionComponent)** | ★v4 新增的挂载级(两级挂载,仅两级):会话级行为单元基类,生命周期随 Session(装配期创建 → dissolve 全灭),由**会话装配**声明(不按模式)。 |
| **每会话一份** | 会话级组件在会话生命周期内是单例:同一会话内一切查询恒返回同一实例;**跨会话不共享**(arena 下 N 个会话各持一份);共享的只能是不可变声明(Spec/Snapshot),有状态实例永不跨会话共享。 |
| **跨级只读** | 实例组件与会话组件按接口**互相只读**查询(实例侧经 SessionView,会话侧经 MatchModeInstanceAccess);**写走端口**——不得写入对方,干预只能走端口(实例级 end()/switchTo(),会话级 requestSwitch()/requestDissolve());跨级引用不得跨切换缓存。 |
| **判据两问** | 会话行为的归属判据:问一——它坏了会话会失守底线(名册错/世界漏/切换不原子)吗?是 → 内核机制;问二——服务器主想要另一种做法吗?是 → 会话级组件(策略)。 |
| **EmptyArenaComponent(空场解散策略)** | ★v4 第一波下放的会话策略:空场持续到设定时长即请求解散(经 requestDissolve);成员回归即重置计时。装配按拓扑:**rotation 不挂 = 永不解散;不挂 = 默认**(与大厅零配置哲学一致)。(**★v1.3-D5**:「装配按拓扑」的框架侧分支废止——挂不挂、`after` 给多少,由**宿主组装 `SessionSpec.sessionComponents` 时声明**(原 `session.dissolve-after-empty-seconds` 键删除);「不挂 = 永不解散」口径不变,★31 机制零改动。) |

## 进程级服务(v4.1)

| 术语 | 定义 |
|---|---|
| **Service(进程级服务)** | ★v4.1 新增的第三类对象,与组件并列:进程生命周期(onEnable 装配 → onDisable 关停),不随任何 Session/Instance 生灭。与组件三条硬边界:①不进组件描述符表——svc().components() 查不到它;②无五钩子派发、不占 tickAll 任何一步(自行经 ServiceLifecycle#start 订阅事件驱动);③与组件/模式双向不得 import(见「服务与组件互不可见」)。归属判据(决断一三问版第四行):**状态必须跨会话聚合,或本就是进程级机制/外部世界翻译** → 进程级服务(全服账本、全局看板、配对队列、经济适配)。 |
| **ServiceRegistry(两阶段装配容器)** | ★v4.1:服务的统一装配容器。阶段一 `install` 仅声明(重复 id/重复类型响亮抛,点名先到者;seal/shutdown 后拒);阶段二 `seal` 依赖校验 → 拓扑排序 → 按序 factory 创建 + start + 注册能力标签;任一工厂/start 抛 → **回滚已启动服务(逆序 stop)再抛**,绝不停在中间态;`shutdown` 逆序 safeStop、幂等、摘除能力标签。**消费面五方法封顶**(install/seal/get/require/shutdown)——上帝注册表是陷阱,扩需求 = 新独立机制。 |
| **ServiceSpec(声明式描述符)** | ★v4.1:声明「这个服务怎么造」:id/type/factory/requires/requiresOptional/capabilities,Builder 构建,构建期校验 id 非空。与组件 Specification 的两处刻意差异:**无 freeze()**——服务 Spec 由 L0 现场构建、只被一个注册表消费,无共享串改问题;**工厂收 ServiceEnv 而非 svc**——服务不持服务集 record,依赖在创建期经 env.dep() 注入(拓扑序保证就绪)。 |
| **ServiceEnv(工厂取物口)** | ★v4.1:服务工厂的取物口,零内容词汇:dep(必需依赖,拓扑序保证就绪;取不到 = 声明与取用不符,响亮抛)/ depOpt(可选依赖,缺席 empty 不抛)/ plugin(平台原语:日志/调度/数据目录)/ cfg(惰性只读配置面 ConfigView)。 |
| **ServiceLifecycle(服务生命周期)** | ★v4.1:可选生命周期接口,start/stop **默认空实现**;纯无状态服务(Facade 类)不必实现;实现者须保证 **stop 与 start 严格对称**。 |
| **ConfigView(惰性只读配置面)** | ★v4.1:ServiceEnv#cfg() 返回的惰性只读配置面,由 L1 ConfigRepository 实现;/reload 后自动读新值。 |
| **服务↔会话三通道** | ★v4.1:服务触达会话只有三条通道,无第四条:①**订阅 SessionEventBus**(SessionCreated/MemberJoined/Left/ModeSwitchStarted/Completed/SessionDissolved/SessionComponentQuarantined——感知生灭的官方通道,订阅句柄在服务 stop 中注销);②**只读快照**(MiniGameApi.sessions() 不可变 record;引擎内可经 SessionService 遍历 + MatchModeInstanceAccess 只读查询;不得持有活实例/活组件引用跨切换,现取不缓存);③**SessionService 受控命令**(switchMode/dissolve/createSession——服务属外部调用方(切换来源②),与 /sm 命令同权;force 是显式选择,走 review 条款)。★v1.1-D10 注:通道③受控命令清单不变;跨 jar 外部调用方经 `MiniGameApi` 的成员操作不构成第四通道(调用方阵营不同,见 19-D2/D10)。 |
| **服务与组件互不可见** | ★v4.1:双向冻结(ArchUnit):组件/模式不得 import engine/service/** 与 service/**;服务不得 import mode/** 与 component/**。服务不是第三级组件挂载级(组件挂载仅两级,A10 原文保留);MatchModeInstanceServices(3 成员)与 SessionServices(2 成员)**不加成员**——组件要消费基础设施走既有先例(L1 适配器 + 包装组件;同款具体先例属宿主扩展,见 Shdf具体玩法设计案/09),不是加 svc 成员。 |

## 引擎三迁组件(v3 从引擎降级为组件的三个机制)

| 术语 | 定义 |
|---|---|
| **PhaseComponent** | 阶段状态机组件:持有阶段表(Timed/Await)数据,推进/转场/计时/PhaseChanged 事件(事件类型住本组件包)。引擎对"阶段"零认知。 |
| **EndGateComponent** | 结束判定门组件:持有 EndCondition 列表按 priority 求值,命中即 `svc().self().end(new EndResult(intent))`。结束是组件的主动声明,不被框架轮询。 |
| **EndCondition / GateVerdict / MatchOutcome** | 判定器(纯函数,产出 GateVerdict)/ 判定产物(结局 + 可选去向)/ **玩法结局词汇**(Kind: WIN/DRAW/NEXT/ABORTED + 胜者 + reason,住 component/endgate——引擎零认知,v3.1 从引擎 EndResult 拆出)。 |
| **EndResult(引擎侧)** | 框架的结束货币:**仅携带可选 TransitionIntent(下一站)**,不含胜负/原因(v3.1 瘦身)。 |
| **RuleBookComponent** | 声明式规则簿组件:持有 Rule(trigger/when/then 纯函数)列表,事件驱动求值。可选件。 |

## 切换与交接

| 术语 | 定义 |
|---|---|
| **SwitchMode(模式切换)** | 框架 API:旧实例组件 onHandoff + close → 名册原地位移 → createInstance + awakeAll + startAll,主线程单 tick 原子,三级回退。 |
| **Handoff(交接)** | 模式间传递数据的类型化键值袋。**实例数据**出实例的**唯一合法通道**(★v4-D10:跨模式数据另可寄居会话级组件——两通道口径;会话组件状态随 dissolve 全灭)。命名空间 = 写入方自己的 id(模式 id / 组件 id);★v3.1 框架不写任何键(HandoffKeys 已删),标准键随主人:`VoteComponent.HANDOFF_RESULT`、`StatsComponent.LAST_SUMMARY`。 |
| **TransitionIntent(转移意图)** | "下一站去哪"的指令:ModeSelection + 携带的 Handoff。由 EndGate 的判定器或外部调用方产出。 |
| **Fallback Mode(回退模式)** | 新实例装配失败时切换去的兜底模式。**配置项**,引擎不认识任何具体模式 id。(**★v1.3/21-D3**:默认值改**空**,框架不设出厂模式——回退去向 = 配置指向的任意已注册模式;为空或未注册 → 回退链跳过一级、直接二级 dissolve。原「默认 lobby」为 v1.2 前口径。) |
| **Min-dwell(最短驻留)** | 两次自动切换之间的最小间隔(默认 100 tick),防切换风暴。 |

## 角色接入(SHDF 宿主扩展,非框架组成)

> 角色系统接入是 SHDF 宿主扩展,见 Shdf具体玩法设计案/;两层术语(桥接层与角色组件)的定义见该目录 CONTEXT-玩法术语.md「角色接入」节及其 08、09 两篇。框架侧仅保留与模式声明相关的能力标签机制,已列于「模式声明层」Capability 条。

## 世界

| 术语 | 定义 |
|---|---|
| **WorldPort(世界端口)** | 世界的实例化/引用计数/释放的端口。只有 SessionService 调用;模式与组件无感。 |
| **世界模板(Template)** | 地图的世界母本(`maps/templates/<id>/`)。副本按 (map, session) 实例化。 |
| **地图注册(registerMap)** | ★v1.2:宿主/伴随插件经 `MiniGameApi#registerMap(Plugin, MapDefinition)` 显式注册地图声明、`unregisterMap(Plugin, String)` 摘除——文件装载之外的**第二输入通道**,与文件式共用 `MapRegistry` 同一命名空间(重复 id 响亮点名先到者)。**地图交成品不交工厂**:`MapDefinition` 纯静态值,直接注册建好的实例合法(与模式必须交 `MatchModeProvider` 工厂相对,20-D1)。注册期校验 id/tags/**模板目录存在性**(宿主注册前须自行解包模板落盘,14.39);卸载走显式 + `PluginDisableEvent` 兜底,**有会话正用 → 摘除被拒**;注册式地图不经 `/sm reload maps`;`WorldPort` 世界生命周期不受注册通道影响(20-D7)。 |
| **ResetStrategy(还原策略)** | 副本处置方式:REUSE_DIRTY(打完复用)或 TEMPLATE_RESTORE(删副本重复制)。 |

## 对外

| 术语 | 定义 |
|---|---|
| **MiniGameApi** | 本框架对第三方插件的冻结公共面(查会话、控切换、注册模式、查战绩),经 ServicesManager 暴露。(**★v1.3**:「查战绩」面随 R1 悬置——归宿主线消费;框架冻结面 7.1 落地时不含 DB 依赖项,回填裁决见 21 §6-Q1。) |

## 历史术语(已废弃,勿在新代码使用)

| 旧术语 | 替代 | 废止于 |
|---|---|---|
| Match / GameMode / MatchPlan / MatchParticipant / MatchEvent / WinCondition / MatchOutcome | MatchModeInstance / MatchMode(聚合根)/ —(已删除)/ Participant / MatchModeInstanceEvent / EndCondition / MatchOutcome(v3.1 复活于 component/endgate,作内容层结局词汇)+ EndResult(引擎侧,仅去向) | v2 |
| `Mode` 接口的 plan()/rolePolicy()/transition()/validateMap()/requires()/joinPolicy()/recordsHistory() | Builder 参数、MatchModeMeta 字段、组件 Spec 构造数据、Spec#checkMap | v3 |
| `Component`(接口,与 Adventure 文本 Component 撞名)/ MatchContext | `MatchModeComponent`(基类)/ `MatchModeInstanceServices`(svc) | v3 |
| 引擎内 PhaseChain/PhaseNode/RuleEngine/胜负求值 | PhaseComponent / EndGateComponent / RuleBookComponent(L3 组件) | v3 |
| RoleLoadoutComponent | 已并入角色组件的 Loadout facet(v3;见 Shdf具体玩法设计案/09 与该目录 CONTEXT-玩法术语.md) | v3 |
| TransitionPolicy(策略对象) | MatchModeMeta.defaultTransition(纯数据) | v3 |
| GameMode 的 META-INF/services 注册 | MatchModeProvider 工厂注册 | v3 |
| Mode / ModeMeta / ModeComponent / ModeInstance / ModeProvider / ModeRegistry / ModeSelection / ModeConfig / ModeInfo | MatchMode / MatchModeMeta / MatchModeComponent / MatchModeInstance / MatchModeProvider / MatchModeRegistry / MatchModeSelection / MatchModeConfig / MatchModeInfo(消除与 Bukkit GameMode 的撞名) | v3.1 |
| HandoffKeys 框架保留键(VOTE_RESULT/LAST_SUMMARY/RETURN_REASON) | VoteComponent.HANDOFF_RESULT / StatsComponent.LAST_SUMMARY / "为何离开"随 MatchSummary 携带(框架不写交接键) | v3.1 |
| EndResult.Kind 枚举(WIN/DRAW/NEXT/ABORTED)+ winnerTeamId | MatchOutcome(component/endgate);引擎 EndResult 仅 `Optional<TransitionIntent>` | v3.1 |
| enum Capability{ ROLE }、MatchModeMeta.LOBBY/COMPETITIVE 常量 | 字符串能力标签(桥接注册 "role")/ 各模式工厂自建 meta | v3.1 |
| 切换 B3 角色兜底 clearRole / engine 端口 RolePort、DisplayPort / engine 配置 role-sample-interval | 已删除——清场唯一闸/消费面/采样间隔均已归玩法线组件与其 modes 配置(见 Shdf具体玩法设计案/08、09) | v3.1 |
| `MatchModeMeta.joinPolicy`(OPEN/SPECTATE/CLOSED 入场枚举)/ JoinPolicy 枚举类型 | JoinControlComponent(#24)+ engine 端口 JoinAdmission(ADMIT/SPECTATE/REJECT,§3.2.1);不挂 = 默认全接纳 | v3.2 |
| `SessionPolicy` 的空场部分(空场判定 / idleTimers 空场) | EmptyArenaComponent(会话级组件,第一波下放);连接追踪与宽限 sweep 仍在内核 | v4 |
| 原(组件单一挂载级)的表述 | 两级挂载:实例级 MatchModeComponent + 会话级 SessionComponent | v4 |
| Java 包根 `com.shdf.match`(12 §12.1 曾注「历史沿用不改」) | 框架 `com.hydrokarbs.*`(主类 `com.hydrokarbs.HydroKarbsPlugin`);宿主另立独立顶级包 `com.shdf.*`(旧根 `com.shdf.match` 不复用) | v1.1(D13) |
| `engine/port/PersistencePort` + `bridge/persistence/PersistencePortImpl` + L5 `data/**`(Database/AsyncExecutor/StatsFlusher/repo/、model/) + build 的 HikariCP/mysql 依赖与 shade + config `database:` 块 + `engine.shutdown-flush-budget-seconds` + `sql/` 资源 | **持久化整体出框架**(21-D1/R1):宿主自建,经自己的组件/服务直连自己的 DB 栈;`StatsComponent` 采集与 `StatsDelta` 出口留框架;★10~★12 命题随宿主线执行;设计资产以 10-数据库存档保全(§10.5 例外留框架) | v1.3(D1) |
| `component/display/**`(DisplayComponent) | **表现层出框架**(21-D2/R2):框架基线移除 `component/display/` 与 `render/` 与 config `display:` 块;组件与渲染器迁宿主(`com.shdf.*` 对应包),数据驱动设计与挂法见 Shdf具体玩法设计案/10-表现层组件.md;施工子阶段 4.3 撤销;P3 组件计数 L3 实数 21→20 | v1.3(D2) |
| `DisplayModel`/`DisplayLine`/`Countdown`/`Progress`(中立表现模型,原住 `com.hydrokarbs.component.display`) | 随 D2 迁 Shdf具体玩法设计案/10-表现层组件.md(标注「自框架 06 §6.2 v1.3 迁入」,原文完整);框架基线无表现层类型 | v1.3(D2) |
