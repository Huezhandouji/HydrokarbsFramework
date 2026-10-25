# 阶段 1 · 引擎内核与架构地基(施工指导)

> HydroKarbs MiniGame Framework · v1.0(自 SHDFMatch 设计案 v4.1 剥离玩法重构;玩法实现见 Shdf具体玩法设计案/)

> 对应路线图:`设计案/16-路线图与验收.md` §16.1 的 **P0(骨架)+ P0.5(三迁组件)**;总览见 `施工计划/README.md` §1/§4。
> 版本基线(全文一致):Java 21 / PaperMC 1.21.11 / Gradle Kotlin DSL / `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`;角色系统接入是 SHDF 宿主扩展,框架基线不含宿主插件坐标(具体插件坐标与原基线 1.0.0/mavenLocal/compileOnly 的存档见 `设计案/00-假设与体检.md` §0.2-A1),见 Shdf具体玩法设计案/;`runTaskTimer` 初始 delay=1L;DB 与文件 IO 走虚拟线程;命令走 Brigadier(`LifecycleEvents.COMMANDS` 注册);调度走 `GlobalRegionScheduler`(普通 Paper 等价,Folia 迁移成本 0,§00-A5)。
> 设计案是唯一权威:本文件夹只回答"按什么顺序做、避哪些坑、拿什么验收",不重抄、不改设计;所有「§x.y」指 `设计案/` 对应文档的章节。

## 1. 阶段目标(含出口闸门)

把 v3 的"两个类 + 一张表"(`MatchMode` 聚合根、`MatchModeComponent` 基类、组件描述符表,§2)变成可编译、可离线测试的工程,并让三件"从引擎降级为组件"的机制(阶段/结束/规则,§16.1-P0.5)以 L3 组件形态落地。

**出口闸门(全部满足才放行进入阶段 2;对应 `施工计划/README.md` §1 阶段 1 行):**

1. 假组件(`TestComponent`)离线跑完一次实例 `awake → start → update → stop` 全生命周期(无需活服,§16.1-P0);
2. `LayeringTest` / `AggregatePurityTest` / `EnginePurityTest` 全绿,且**此后任何阶段不得放宽断言**(`施工计划/README.md` §3-4);
3. 三迁组件各自离线单测通过:阶段表驱动假流程、EndGate 命中调 `svc().self().end()`、规则簿离线求值(§16.1-P0.5);
4. `gradlew build` + `runServer` 冒烟通过(插件 enable 日志可见,1.1 建立、后续子阶段复验)。

## 2. 子阶段清单

| 编号 | 标题 | 核心产出 | 出口验收 |
|---|---|---|---|
| 1.1 | 工程骨架与构建链路 | `build.gradle.kts`(§12 依赖坐标 + shadow 打包)、`settings.gradle.kts`/`gradle.properties`、`plugin.yml`(§12 原样:commands;v1.0 起框架基线无 softdepend,宿主扩展线依赖由 Shdf 侧施工阶段引入)、主类三件空装配(`HydroKarbsPlugin`/`HydroKarbsLoader`/`Bootstrapper`)、`util/Threads` 主线程单点回程(§10.5) | `build` 绿;`runServer` 冒烟 enable 日志可见;★8(Threads 单点回程口成立) |
| 1.2 | 聚合根与组件契约 | `MatchMode`(final 聚合根 + Builder + `MatchModeMeta` + `ComponentEntry`,§2.3)、`MatchModeComponent` 基类 + `Specification` + `Factory`(§2.4)、`MatchModeInstanceServices`/`MatchModeInstancePort`/`ComponentLookupPort`/`MatchModeInfoPort`(§2.5)、`AttributeBag`(§2.7)、`engine/port` 其余端口(`ConfigPort`/`PlatformPort`/`WorldPort`;★v1.3:原 `PersistencePort` 已删,21-D1)、引擎所需的纯数据件(`Handoff`/`EndResult`/`TransitionIntent`/`MatchModeSelection`,§3.3/§3.4) | 全部类型编译绿;依赖校验算法与 §2.6 逐行一致(算法测试落在 1.4) |
| 1.3 | 实例容器-事件总线与节拍器 | `MatchModeInstance`(容器不持有玩法状态,§12.2/§2.8-决断三)、`ComponentRegistry`(依赖校验执行 + 声明序派发 + 登记资源回收,§2.6)、`MatchModeInstanceEvent` 开放根 + Structural 封闭子族 + `Custom`(§12.2)、`MatchModeInstanceEventBus`(订阅者异常隔离,§11.3)、`SessionEvent`/`SessionEventBus`(§3.1)、`EngineTicker`(全局单任务、delay=1L、切换唯一驱动点,§12.2)、`MatchModeInstanceClock`;`engine/session` 编译壳(`Session`/`SessionService` 等的签名落点) | ★6 close 幂等、★7 清场逆序且异常不中断;事件总线隔离单测;Ticker 驱动序单测 |
| 1.4 | 架构冻结测试 | `LayeringTest`(§16.2 ArchRule)、`AggregatePurityTest`(反射冻结 9 方法,§2.8-决断二)、`EnginePurityTest`(禁词 Phase/Rule/WinCondition/EndCondition/Role/Team/Score/Objective,★24)、`EngineHasNoModeStringsTest`(含 `lobby`,★4/★13)、`MatchModeInstanceServicesMembersTest`(★25)、`AttributeNamespaceTest`(★26)、`ComponentResolutionTest`(§2.6) | 七个测试全绿;★1/★2/★3(规则先建)/★4/★22/★24/★25/★26 首次建立 |
| 1.5 | 三迁组件 phase-endgate-rulebook | `component/phase/PhaseComponent`+`PhaseSpec`(Timed/Await 阶段表、`PhaseChanged` 住组件包)、`component/endgate/EndGateComponent`+`EndCondition`+`GateVerdict`+`MatchOutcome`(§3.4)、`component/rule/RuleBookComponent`+`Rule`;`TestComponent` 测试件 | 假组件离线跑完全生命周期(P0 闸门);三组件离线单测(§16.1-P0.5);★9 规则可离线测 |
| 1.6 | 进程级服务装配 | `engine/service/`(`ServiceRegistry`/`ServiceSpec`/`ServiceEnv`/`ServiceLifecycle`/`ServiceDependencyException`,18 §3 契约骨架)、`engine/port/ConfigView`(惰性只读配置面)、`service/` 参照服务(`GlobalLedger` 上报式/`GlobalBoard` 拉取式) | ★34~★40 离线全绿(★34~★39 六个新测试类 + ★40 `LayeringTest` 增补);`gradlew build` 绿 + L0 接线样例编译通过(前置 = 1.2/1.3;不阻塞阶段 2–4,18 §8) |

## 3. 与其他阶段的接口

**上游(本阶段的输入)**:无上游施工阶段——本阶段是工程起点。输入 = 设计案 v4.1 全文 + `CONTEXT.md` 术语表(v4.1)+ 立项源码体检存档(`设计案/00-假设与体检.md` §0.1「历史案例存档」:聚合根零行为与 svc 注入四条件的已验证形状,即 §0.1 体检表最后三行,结论已吸收进 §2 正文)。原「参照物 = 宿主角色插件源码」的参照关系属 SHDF 宿主扩展线,非框架输入——宿主侧出处锚点已随 `1.2-聚合根与组件契约.md` 的参照框注归档(逐条对照表见 Shdf具体玩法设计案/09-角色组件层.md §9.2);框架施工以 `设计案/02-核心概念.md` §2.3/§2.4/§2.5 的代码块与签名为唯一权威,不依赖宿主工程源码。

**下游(本阶段向后续阶段承诺,精确到子阶段;与阶段 2 README §3 的期望对齐)**:

- 对 **阶段 2(会话层与切换)**:1.1 的 `util/Threads` 与 1.3 的 `EngineTicker`(`tickAll` 驱动序 = `runPendingSwitch → tickCurrentInstance → idleTimers`,§12.2);1.2 的聚合根(仅 `verifyDependencies`/`createInstance` 两方法,§2.3)与 `MatchModeComponent.Specification#checkMap`(§2.4)——2.2 注册闸与 2.3 阶段 A 的落点;1.3 的 `MatchModeInstance`(`awakeAll`/`startAll`/`tick`/`requestEnd`/`close`,§12.2)——2.3 的 B 阶段只是调用方;1.4 的冻结测试族持续对抗、不得放宽;1.5 的三迁组件是 2.5 大厅组件表的现成件(2.5 属玩法线,已迁 Shdf 侧 `Shdf具体玩法设计案/施工计划/`——大厅的实现见 Shdf具体玩法设计案/)。
- 对 **阶段 3(角色线,整阶段迁 Shdf 侧 `Shdf具体玩法设计案/施工计划/`)/阶段 4(组件线)**:1.2 的 `MatchModeComponent` 基类与 svc 三成员服务集是所有组件的唯一基座;1.2 的 `AttributeBag`(命名空间=组件 id)与 1.4 的 `AttributeNamespaceTest` 是 ★26 状态唯一纪律的执法点;1.3 的 `MatchModeInstanceEvent` 开放根是各组件包自定义事件词汇(如 `PhaseChanged`/`RoleKillEvent`)的根接口。
- (★v1.3:本行废止——`PersistencePort` 已删、阶段5 框架半段迁宿主线,总览 §11;原文:)对 **阶段 5(持久化)**:1.2 的 `PersistencePort`/`ConfigPort` 接口落点(成员面按 §10.3.3 等扩充,已有签名不改)。
- **所有权边界**:`engine/session` 在本阶段只有"数据件 + 编译壳"(见 1.2/1.3 的施工步骤);`Session` 状态机、`SessionServiceImpl`、`SwitchResult`、`CapabilityRegistry`、`MatchModeRegistry`、模式注册与配置装载全部归阶段 2。阶段 2 若调整壳的签名,以不破坏本阶段已绿测试为界。

## 4. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 引擎回流状态机:实现中发现"在引擎加个 phase 回调最方便"(§14.24) | 1.4 的 `EnginePurityTest` 禁词扫描先于 1.5 的三迁组件建立;组件感知阶段的两条合法路径只有订阅 `PhaseChanged` 或 `getComponent(PhaseComponent.class)`,均不过引擎 |
| 聚合根长行为:顺手给 `MatchMode` 加方法(§14.23) | 1.2 的形状逐行对照参照物 `core/Role.java`;1.4 的 `AggregatePurityTest` 反射冻结公开方法集 = 9 个(§2.8-决断二),新增即红 |
| 离线测试碰 Bukkit 静态(`Bukkit.getScheduler()` 等)无法跑 | 纪律:引擎/组件的全部离线测试只走纯 Java + 测试假件;调度类接线(`EngineTicker.start`/`Threads` 回程)只做冒烟,单测以直接调用驱动序方法替代(1.3 给出做法) |
| ArchUnit `..mode../..component..` 通配会把引擎自身 `engine.mode`/`engine.component` 子包卷进禁止集(含 `MatchMode`→`ComponentEntry` 自依赖),照抄 §16.2 字面规则会红灯 | 1.4 给出锚定到 `com.shdf.match.mode../component..` 的等价写法,命题不变(★1/★2);已列入待裁决 |
| `plugin.yml` 的 `commands:` 段(§12 原样)与 Brigadier 注册同名命令潜在冲突(PaperMC 技能 §5 反模式) | 1.1 起服冒烟时检查重复注册告警;裁决记录进 ADR,已列入待裁决 |
| 版本号凭记忆写错(shadow 等设计案未钉版本的插件) | 已核时点事实:`com.gradleup.shadow` 最新 9.6.1(2026-07-22)、`xyz.jpenilla.run-paper` 3.0.2 真实存在(设计案钉定,保留);写入 build 前以 plugins.gradle.org 与发布页再核(参照 papermc 技能 §1"不要背版本号") |
