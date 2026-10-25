# 阶段4-共享组件齐活(施工计划)

> HydroKarbs MiniGame Framework · v1.0(自 SHDFMatch 设计案 v4.1 剥离玩法重构;玩法实现见 Shdf具体玩法设计案/)
>
> **★v1.3 影响注(2026-10-25,框架基础设施瘦身,现行口径优先)**:**子阶段 4.3(表现层 DisplayComponent)撤销**——表现层整体出框架,`component/display/**`、`render/**` 与 `display:` 配置块移出框架基线,设计资产迁 `../../Shdf具体玩法设计案/10-表现层组件.md`(保全不销毁);本阶段范围收缩为 4.1/4.2/4.4/4.5 四个子阶段,**L3 组件计数 21→20**(§6.1 #12 行迁出),出口闸门按 20 行执行;★10~★12(数据库三验收)与本阶段无涉但同期归属迁宿主线。变更全文见 `../../21-v1.3修正案-框架基础设施瘦身与配置中立化.md`,映射见总览 `../README.md` §11。
> 对应路线图:**P3 组件齐活**(设计案v2/16-路线图与验收.md §16.1);出口闸门见施工计划总览 §1 阶段4 行。
> 引用缩写(全文通用):「§N.M」= `设计案v2/NN-标题.md` 第 N.M 节(其中 §4/§5/§8/§9 已随玩法线迁 `Shdf具体玩法设计案/`,引用处标注「Shdf 侧」并按编号指向,如「Shdf 侧 05 §5.2」);「★N」= §16.2 的第 N 条机械验收;「#K」= §6.1 组件矩阵第 K 行。
> 术语以 `设计案v2/CONTEXT.md`(v1.0)为准;版本基线:Java 21 / PaperMC 1.21.11(`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`)/ Gradle Kotlin DSL;调度不新增周期任务(统一 EngineTicker 1-tick 节拍,§14.6),命令走 Brigadier(`LifecycleEvents.COMMANDS`),DB/文件 IO 走虚拟线程(本阶段不触库)。
> v4 影响:无新增子阶段;2.6 会话级组件层必须先于本阶段完成(交付顺序约束)。
> v4.1 影响:无。

## 1. 阶段目标(含出口闸门)

**目标**:把 §6.1 的 23 组件矩阵从"阶段1~3 已交付 6 行"补齐为"可用组件库":

| 已有(前序阶段,本阶段只回归) | 本阶段新增(★v1.3:14→13 行——#12 迁出) | 本阶段完整化 |
|---|---|---|
| #4 PhaseComponent、#5 EndGateComponent、#6 RuleBookComponent(阶段1);#10 角色组件(SHDF 扩展线,阶段3,详见 Shdf具体玩法设计案/09);#11 VoteComponent(阶段2 最小版);#14 IdleActivitiesComponent(阶段2) | #1 Team、#2 Score、#3 Respawn、#7 Objective、#8 Region、#9 Interaction、#13 Stats、#15 Disconnect、#16 Visibility、#17 RegionGuard、#18 Round、#19 Economy、#23 PlayerStats(★v1.3:原「#12 Display」迁出——4.3 撤销,迁 Shdf 侧 10) | #11 VoteComponent(双 UI/可改票/平局策略/局末选图形态) |

- **计数口径(★v3.2 更新)**:§12.1 的包结构下,§6.1 的 24 行中有 21 行住 L3 `component/**`(v3.2 增 #24 JoinControlComponent);#20/#21/#22 三个宿主模式专用件住宿主 `mode/**` 子包(§12.1;SHDF 玩法线,随 Shdf 侧阶段5/6 落地,见 `../../Shdf具体玩法设计案/施工计划/`)。本阶段出口按 **L3 21 行**执行,#20/#21/#22 的生命周期单测挂账至 Shdf 侧 5.1/6.x 子阶段文档的 DoD。
- **宿主模式 Display 回接线(玩法线遗留;★v1.3:改全程归宿主线)**:阶段2 遗留——宿主大厅工厂的 Builder 补一行挂自己的 display 组件(骨架见 Shdf 侧 04 §4.2 与 10-表现层组件);原「4.3 交付读口后的宿主侧回接」口径随 4.3 撤销作废,回接全程在宿主工程内完成。

**共同纪律(每个组件都必须同时满足,写进每个子阶段的 DoD)**:

1. **状态唯一**:玩法状态只住本组件,实例容器不持有(§2.8-决断三);AttributeBag 命名空间 = 组件 id(§2.7),跨组件一律走状态主人的强类型读口 `getComponent(X.class)`(★26;§14.26)。
2. **生命周期幂等**:awake 只解析依赖零可见副作用、start 才发生玩家可见副作用、update 无登记副作用、stop 与 start 严格对称(§2.4;SHDF 宿主扩展组件的逐钩子纪律与此同构,详见 Shdf具体玩法设计案/09 §9.4)。
3. **登记资源随 stop 回收**:事件订阅经 `svc().self().events()`、延时任务经 `svc().self().later()`、显示登记进 DisplayComponent(★v1.3:display 组件迁 Shdf 侧 10——登记口仍属框架组件基类,宿主 display 组件同样经登记口回收)——返回后框架统一回收(§2.5;§12.1 `reclaimRegisteredResources`)。
4. **离线单测**:全部组件测试不起服(假 MatchModeInstanceServices 夹具,阶段1 已建;★9)。
5. **组件内容盲**:`component/**` 源码不出现任何宿主模式词汇与 mode id 字面量(§1.2-L3 判据;★4/★24 源码扫描持续生效)。模式差异只经 Spec 构造数据进入。

**出口闸门(不通过不放行,对应施工计划总览 §1)**:

- [ ] L3 **20 行**(★v1.3:21→20,#12 DisplayComponent 迁出)组件各有生命周期幂等 + 登记资源回收单测(awake×2/start×2/update/stop×2,断言副作用恰好一次);
- [ ] ★4/★24(引擎与组件无 mode 字面量/内容词汇)、★26(AttributeNamespaceTest)、★6/★7(close 幂等/清场逆序)回归全绿;
- [ ] §12.1 的中性声明样例(`ExampleModes`,见 `设计案v2/12-代码骨架.md` §12.1)可作为装配对照:本阶段每个组件都能被该样例的对应 `addComponent(...)` 行消费(宿主模式工厂本体属玩法线);
- [ ] #20/#21/#22 的生命周期单测挂账到 Shdf 侧 5.1/6.x 子阶段文档的 DoD(见 `../../Shdf具体玩法设计案/施工计划/`;若届时文档未含,视为该阶段闸门未过)。

## 2. 子阶段清单与执行序

| 编号 | 标题 | 核心产出 | 出口验收 |
|---|---|---|---|
| 4.1 | 队伍与计分族 | `component/team/`(TeamComponent/TeamId/TeamBalancer)、`component/score/`(ScoreComponent/ScoreObjective/RoundComponent/EconomyComponent)、`component/respawn/`(RespawnComponent/RespawnPolicy) | 各组件离线单测(分配/加权/连杀/三重生策略/回合回跳不触发切换);ScoreObjective 与 4.2 的 Objective 接口对接 |
| 4.2 | 目标区域与交互族 | `component/objective/`(ObjectiveComponent/Objective/Progress/ProgressTracker)、`component/region/`(RegionComponent/RegionGuardComponent)、`component/interact/`(InteractionComponent/Trigger/ItemMatcher)+ `listener/InteractionListener.java`、`listener/ProtectionListener.java` | 区域形状/进出事件、目标完成事件、长按读条三事件源(§6.3)离线可测;IdleActivities 回接点确认 |
| ~~4.3~~ | **表现层 DisplayComponent(★v1.3 撤销)** | ~~`component/display/` + `render/` + `display:` 配置块~~——迁 `../../Shdf具体玩法设计案/10-表现层组件.md`;文档存档(头部撤销注) | (撤销)宿主侧落地随 Shdf 侧施工;★13/★4 框架断言不受影响(display 从未是引擎件) |
| 4.4 | 投票与统计族 | `component/vote/` 完整化、`component/stats/`(StatsComponent/StatLedger/PlayerStatsComponent/MatchSummary)+ `data/model/`(StatsDelta/StatColumn)、`StatsComponent.LAST_SUMMARY` | ★18 交接键经切换可读;账本→StatsDelta→假端口收口;战报组装含结局与离开原因 |
| 4.5 | 生命周期辅助组件(含入场控制) | `component/lifecycle/`(DisconnectComponent/CleanupComponent)、`component/visibility/`(VisibilityComponent)、`component/join/`(JoinControlComponent,★v3.2 实现 2.3 的 JoinAdmission 端口)+ 全族 21 行生命周期幂等回归套件 | 四组件单测;LifecycleIdempotencyTest 跑满 21 行;出口闸门全清单核验 |

**推荐执行序(★v1.3:4.3 撤销后)4.2 → 4.1 → 4.4 → 4.5**。理由(均为编译期硬依赖):4.2 的 `Objective` 接口是 4.1 `ScoreObjective` 的供给方(依据 Shdf 侧 05 §5.2"current 由 ScoreComponent 驱动");4.2 的 `ProtectionListener` 是 4.4 投票 GUI 的取消点击的保护面(§15.1)。(原「4.3 的示例构建函数战报行读 4.4 的 `StatsComponent.LAST_SUMMARY`」依赖随 4.3 撤销移除;4.4 战报组装不再有框架侧 display 消费方——其 `LAST_SUMMARY` 交接键复验走 ★18 既有断言。按编号顺序施工亦可,但须接受 4.1 末(ScoreObjective)一处跨子阶段回填点,该文档已标注。)

## 3. 与其他阶段的接口

**上游(本阶段消费,已存在,不得改动其契约)**:

| 来源 | 提供物 | 本阶段消费点 |
|---|---|---|
| 阶段1(1.1/1.3/1.4/1.5) | `MatchModeComponent` 基类与 `Specification`(requires/checkMap/freeze,§2.4)、`MatchModeInstanceServices` 三成员(§2.5)、`MatchModeInstanceEventBus`(订阅随 stop 回收)、`AttributeBag`(命名空间校验,§2.7)、EngineTicker 1-tick 节拍与 `later()` 登记(§14.6)、close 幂等+逆序 stop+资源回收(★6/★7)、PhaseComponent(`jump`/`countdown`/PhaseChanged)、EndGateComponent(`outcome()` 读口,§3.4)、RuleBookComponent、离线测试夹具(假 svc) | 全部组件的骨架与测试基座 |
| 阶段2(2.3/2.4/2.5) | `SessionService#switchMode` 两阶段切换(§3.2)、`Handoff`(键随主人,§3.3)、`WorldPort`+`ResetStrategy`(§3.7,CleanupComponent 的 REUSE_DIRTY 配套)、`map/` 包(MapDefinition/NamedRegion/InteractPoint/Spawns/bounds,RegionComponent 的数据源)、ConfigRepository(maps/*.yml + modes/*.yml 解析)为框架侧;IdleActivitiesComponent 与宿主大厅模式工厂属 2.5(SHDF 玩法线,迁 `../../Shdf具体玩法设计案/施工计划/`;IdleActivities 组件本体住 L3 `component/**`,框架保留) | 4.2 区域/交互、4.3 回接线、4.5 Cleanup |
| 阶段3(SHDF 扩展线,整阶段迁 `../../Shdf具体玩法设计案/施工计划/`) | 宿主角色组件的强类型读口(敌对判定/角色标识等,Shdf 侧 09 §9.3)、宿主结构事件词汇住宿主扩展组件包(§12.2 Structural 扩展点,Shdf 侧 09) | 框架承接:4.1 的宿主能力缺席回落说明、4.4 的击杀计数消费宿主结构事件——一律经包装组件读口,不 import 桥接(★23) |

**下游(本阶段承诺)**:

| 去向 | 承诺 | 精确接口 |
|---|---|---|
| 阶段5 的 5.1/5.2(SHDF 玩法线,迁 `../../Shdf具体玩法设计案/施工计划/`) | 首个宿主模式只需 Builder 声明即可拿到队伍/计分/重生/显示/投票/统计全量能力 | 框架承接:5.1 消费 4.1~4.4 全部 Spec;跨模式交接走 `VoteComponent.HANDOFF_RESULT` 与 `StatsComponent.LAST_SUMMARY`(★18,交接复验随 Shdf 侧端到端验收执行) |
| ~~阶段5(5.3/5.4 持久化)~~(**★v1.3:迁宿主线,总览 §11**) | `StatsDelta`/`StatColumn` record(落位改 `component/stats`,★v1.3)与 StatsComponent 的账本出口先于库实现存在 | ~~5.4 的 StatsFlusher 对接~~ → 宿主侧持久化对接 `StatsComponent` 的 delta 产出(组件侧落库通道见 §5 待裁决-3) |
| 阶段6(SHDF 玩法线,整阶段迁 `../../Shdf具体玩法设计案/施工计划/`) | 宿主模式只经本阶段 L3 读口(Round/Economy/Interaction/Region/Objective)组装,`engine/**` 与既有 `component/**` 零改动 | 框架承接:宿主显示构建函数属玩法线,随其宿主专用组件落地(构建函数形态照 §6.2;实现与规格见 Shdf 侧) |
| 阶段7(7.1/7.2 API 冻结与第三方) | "新增模式只写 30 行构建函数"(§6.2)的承诺由本阶段兑现 | 新模式的显示/计分/目标全部经本阶段读口组装,`engine/**` 零改动 |

**并行约束**(施工计划总览 §2):阶段3(SHDF 扩展线,Shdf 侧)与阶段4 互不依赖、文件集不相交——`component/role/**`、`bridge/role/**`、`listener/RoleSignalListener.java` 归宿主扩展线(见 `../../Shdf具体玩法设计案/施工计划/`);`listener/StatsSignalListener.java`(seam 过渡形态)与 `InteractionListener`/`ProtectionListener` 的占位文件也由该线建立,阶段4 对这三者是**填实而非新建**——`StatsSignalListener` 换 `StatsComponent` 直查并删 DamageLedger seam,`InteractionListener`/`ProtectionListener` 按占位首行标注的目标组件填实(§12.2 终态);其余 `component/**` 归阶段4。动工前 `ls listener/` 核对无同名文件冲突。

## 4. 风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| R1 | 组件间绕过状态主人裸写 AttributeBag(★26 最易破) | 阶段1 的命名空间断言(§2.7)+ 每个子阶段一条 ArchUnit/单测:组件 A 不得引用组件 B 的 `AttributeBag.Key` 常量(§14.26);跨组件一律 `getComponent` 读口 |
| R2 | 回合级判定器的装配归属张力(Shdf 侧 05 §5.2 vs §12.1 工厂示例) | 未裁决前按 4.1 文档的默认拆分实现(RoundComponent 求值回合级、EndGate 只挂整场级),裁决后一天内改齐;见 §5 待裁决-1 |
| R3 | (★v1.3:随 4.3 撤销迁宿主线)Display 刷新开销与发包放大 | 宿主侧 display 组件沿用原缓解:refresh-interval=10 + diff 渲染;高频事件路径"先廉价判断再做事"(§15.1;详见 Shdf 侧 10) |
| R4 | 长按读条事件源自创走样 | 严格照 §6.3 的已验证形态:通道结算在 EngineTicker 1-tick 节拍推进 + PlayerMoveEvent/EntityDamageEvent 打断 + RIGHT_CLICK_AIR 松手;优先级矩阵照 §15.1 |
| R5 | (★v1.3:随 4.3 撤销全程归宿主线)宿主显示构建函数依赖尚未落地的宿主专用组件而不可编译 | 中性示例与各宿主模式构建函数均随宿主工程交付(Shdf 侧 10 与其施工计划);框架阶段不再涉显示构建函数 |
| R6 | 生命周期幂等测试写虚(只测"不抛异常") | LifecycleIdempotencyTest 必须断言副作用计数(订阅数/later 句柄数/显示注册数在 stop×2 后为 0,二次 start 不重复),4.5 给统一套件 |
| R7 | 阶段3 并行合并冲突 | 见 §3 并行约束;文件清单在动手当天与 Shdf 侧阶段3 文档核对一遍 |

## 5. 待裁决问题

1. **回合级判定器的装配归属**:Shdf 侧 05 §5.2 明文"回合内的模式判定只结束回合、不触发 switchMode,只有整场结束才产生面向框架的 EndResult"(原文见 `../../Shdf具体玩法设计案/`),且 §6.1-#18 写明 RoundComponent"内部消费 #4/#5";但 Shdf 侧 05 的模式工厂声明示例把全部判定器(含回合级)注入 `EndGateComponent.Spec`,而 §3.4 的 EndGateComponent.update() 命中即调 `svc().self().end(...)`——两处对"回合级判定由谁持有、在哪儿求值"给出了不可同时成立的装配。4.1 默认按 Shdf 侧 05 §5.2/§6.1-#18 拆分(回合级住 RoundComponent),请统筹裁决并回改 Shdf 侧示例或补充 EndGate 的分诊语义。
2. **P3 交付计数口径(已由 v3.2 解决)**:原张力(§16.1-P3"23 个" vs §12.1 的 mode/** 三行)已随 v3.2 的 JoinPolicy 下放一并修正——设计案 §16.1-P3 现为"`component/**` 24 个(#20/#21/#22 住 mode/**,L3 实数 21)";施工计划总览与阶段4 闸门均按 21 行对齐,无遗留。
3. **组件访问引擎全局端口的合法通道**(★v1.3:本问的 PersistencePort 半边已随 R1 消解——接口已删、落库归宿主;ConfigPort 半边维持未决):原 §10.3.1 要求"组件只调 PersistencePort"(该接口 v1.3 已删);4.4(StatsDelta 出口)的消费方为宿主侧持久化(经 Spec 构造数据传入,无引擎端口);"组件经什么口拿 ConfigPort"仍请统筹裁决(MatchModeInstancePort 扩成员?实例结束时框架经 EngineServices 拉取?),避免宿主线接线时返工。
