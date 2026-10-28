# HydroKarbs MiniGame Framework(简称 **HKF**)—— 《我的世界》多模式小游戏纯框架架构设计案

> HydroKarbs MiniGame Framework · v1.7(现行;v1.6 于 2026-10-27 API 面显式注册模式与会话组件;v1.5 于 2026-10-27 REUSE_DIRTY 副本随会话解散销毁;v1.4 于 2026-10-27 API 面收敛与地图注册单通道;v1.3 于 2026-10-25 框架基础设施瘦身与配置中立化;v1.2 于 2026-10-23 地图注册通道;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充;v1.0 于 2026-10-22 自 SHDFMatch 设计案 v4.1 剥离玩法重构;玩法实现见 [Shdf具体玩法设计案/](Shdf具体玩法设计案/))
> 历史沿革(旧代号见 [17-v4变更指示书](17-v4变更指示书.md)、[18-v4.1修正案](18-v4.1修正案-进程级服务层与ServiceRegistry.md) 与 [docs/adr/](docs/adr/) 历史存档,存档中旧名有意保留;历史修正案 [19-v1.1](19-v1.1修正案-框架独立插件化拆分与API面扩充.md)、[20-v1.2](20-v1.2修正案-地图注册通道.md)、[21-v1.3](21-v1.3修正案-框架基础设施瘦身与配置中立化.md)、[22-v1.4](22-v1.4修正案-API面收敛与地图注册单通道.md)、[23-v1.5](23-v1.5修正案-REUSE_DIRTY副本随会话解散销毁.md)、[24-v1.6](24-v1.6修正案-API面显式注册模式与会话组件.md);现行修正案见 [25-v1.7修正案-能力标签机制退场与会话组件桥接.md](25-v1.7修正案-能力标签机制退场与会话组件桥接.md)):文档版本 v1.7(2026-10-28,能力标签机制退场与会话组件桥接——标签机制全链退场,外部能力 = 会话组件桥接,25/ADR-0013;v1.6 于 2026-10-27,API 面显式注册模式与会话组件——模式/会话组件经四法显式注册,ServicesManager 提供方通道退场,24/ADR-0012;v1.5 于 2026-10-27,REUSE_DIRTY 副本随会话解散销毁,23/ADR-0011;v1.4 于 2026-10-27 API 面收敛与地图注册单通道,22/ADR-0010;v1.3 于 2026-10-25 框架基础设施瘦身与配置中立化,21/ADR-0009;v1.2 于 2026-10-23 地图注册通道,20/ADR-0008;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充,19/ADR-0007;v1.0 于 2026-10-22 剥离玩法重构;v4.1 于 2026-10-21 进程级服务层与 ServiceRegistry;v4.0 于 2026-10-17 会话级组件层;v3.2 于 2026-10-17 JoinPolicy 下放为组件;v3.1 于 2026-10-16 命名去歧义 + 框架内容盲收尾;v3.0 于 2026-10-15 由单文档拆分为设计案文件夹)
> 基线:Java 21 / PaperMC 1.21.11(★v1.3:MySQL 8.x + HikariCP 基线已随持久化整体出框架,归宿主线自建,21-D1)。角色系统接入是 SHDF 宿主扩展,见 [Shdf具体玩法设计案/](Shdf具体玩法设计案/)。
> 术语表见 [CONTEXT.md](CONTEXT.md);重大决策见 [docs/adr/](docs/adr/)。

## v1.7 版本定位(能力标签机制退场与会话组件桥接)

**能力标签机制全链退场,外部能力改由会话组件桥接表达**(A 项新特性;代码已随 v1.7 施工窗口完成,git 实况为准)。**判据 = 字符串契约不严谨**(经用户裁决):标签防撞只靠词汇纪律自证,撞名/拼写错误要到**运行期切换 A2 才炸**;字符串层与框架既有的组件类型化依赖图(`requires`/`requiresOptional` + 装配期响亮 + D9 quarantine)构成平行的弱机制。**退场清单**:`MiniGameApi#registerCapability`/`#capabilities` 两法(冻结面 19 → **17 法**,`API_VERSION` 5 → **6**)、`CapabilityRegistry` 机制件、切换 A2 校验步(阶段 A 失败路径自此为 A1/A3~A6)、`MatchModeMeta.requiredCapabilities` 首分量(模式工厂 Builder 声明语言变更:构造实参少一枚)、`MatchModeInfo` 快照分量、`SessionComponent.Specification#capabilities` 与 `ServiceSpec#capabilities` 两端声明(服务 seal/shutdown 注册随注消失)、`capabilityClaims` 簿记、`ProviderUnloadListener` 能力块(兜底四族 → **三职责块**)。**新形态(会话组件桥接)**:提供方(或宿主自持防腐层)经 `registerSessionComponents`/`SessionRecipe` 贡献类型化桥接组件(外部类型 import 只住桥接组件类内部——★23 单点纪律泛化),消费方组件 `requires/requiresOptional` 类型化声明,装配期缺必需依赖响亮抛、运行期组件异常 quarantine(会话不解散);软依赖由 ★21 类型化 `requiresOptional` 独自承载(机制已在框架内,非新增)。**语义位移(裁决要点)**:能力校验从「每次切换重查(进程级新鲜度)」改为「**创建时绑定 + 运行期降级/quarantine**」——provider 摘除只影响后续会话,存量会话由桥接组件自身降级承接(D14 既有口径:无运行期拆卸,随 dissolve 自然消亡)。**边界不变**:引擎机制零新增(切换管线/两级挂载/三通道/服务层/摘除回退编排零触碰;24 号显式注册四法原样且成为桥接唯一「进」口);`SwitchResult` 三拒绝码枚举原样。**冻结测试合法更新**:★19 FROZEN 17 行、★44 BASELINE 重录(删能力两法 + meta/info/Spec/Snapshot capabilities 共 6 行)、`API_VERSION=6`(背书 = 25 §3/§5)。决策记录见 [docs/adr/0013](docs/adr/0013-capability-tags-removal.md)(Accepted;强类型枚举——历史已废止一次、封闭集不可跨插件扩展,否决;框架定义类型化端口——内容词汇进 engine 触红线,否决;双轨保留——One-Version 否决;权衡 = 失去零编译耦合与每切换重查的进程级新鲜度,由宿主自持桥接与组件降级承接),验收处置 ★32 就地废止注 + 新增 ★60(类型化桥接;首建于 ★v1.7 退场窗口,持续验证 = `ComponentResolutionTest`/`SessionComponentQuarantineTest`/v6 快照),完整变更见 [25-v1.7修正案](25-v1.7修正案-能力标签机制退场与会话组件桥接.md)(变更指示,现行,非历史存档)。

## v1.6 版本定位(API 面显式注册模式与会话组件)

**注册通道显式化:模式与会话组件经 `MiniGameApi` 显式注册四法进出,跨插件 ServicesManager 提供方通道退场**(A 项新特性;代码已随 v1.6 施工窗口完成,git 实况为准)。**四法**:`registerMode(Plugin, MatchModeProvider)`——注册工厂不注册成品(§13.4;聚合根由框架以自己的配置源 `build(cfg)` 产出,`modes/<id>.yml` 生命周期归框架,与地图「交成品不交工厂」相对——差异 = 配置生命周期归属),注册闸 build → verifyDependencies → id 查重**全响亮**(原「启动期响亮 / 运行期事件通道不上抛」双口径合一;onEnable 注册失败 = 拒载信号);`unregisterMode(Plugin, modeId)`——只准摘自己注册的 id(越权含本 jar 出厂模式 → 响亮拒;不存在 → 幂等 no-op),摘除执行**回退编排**:`session.fallback-mode` 已配置且已注册 → force 切换回退(豁免 min-dwell),否则 `FALLBACK_DISSOLVE` 解散,回退被拒 WARN 放弃不自旋;`registerSessionComponents(Plugin, specs)`——干跑合并校验(组件 id 查重/必需依赖)响亮抛、失败不留半收录态,同一提供方重注册 = 幂等重收录;`unregisterSessionComponents(Plugin)`——幂等,后续会话不再合并、已装配会话不受影响。**SPI 退场**:`SessionComponentProvider` 接口与 `ProviderServiceListener` 删除,`MatchModeProvider` 降格为 `registerMode` 载荷(接口本体与 07 §7.2 白名单资格不变),`MatchModeRegistry#discover()` 的 ServiceLoader 本 jar 半边保留(清单现存无工厂——框架不设出厂模式,21-D3)。**依赖反转**:第三方 `depend: [Hydrokarbs]` + onEnable 首行 `load(MiniGameApi.class)` + 版本检查(14.36)后显式登记——原「先启补收录」场景退役。**disable 兜底四族**:`ProviderUnloadListener` 按 `PluginDisableEvent` 按提供方摘除能力 / 模式(逐 id 同回退编排)/ 地图(正用拒绝 = 保留并 WARN)/ 贡献(后续会话不再合并)。**边界不变(B 项,零触碰)**:引擎内部机制(会话/切换/两级挂载/三通道/世界/服务层)零改动;摘除回退走 §3.2 既有二级回退链(`fallback-mode` 空值语义 21-R3 同款);`SessionRecipe` 形状与合并序(已收录贡献(注册序)→ recipe.extra)原样;模式仍无权贡献会话组件、无运行期增删(v4-D4);第三方查询会话组件实例维持不开放(19-§7-Q5);`API_VERSION` 4 → **5**(删公共接口 `SessionComponentProvider` = 破坏性,零过渡窗口;消费方保护面 14.36 不变;★19/★44 冻结快照随 v5 面合法重录——FROZEN 19 行/BASELINE 重录,背书 = 24 §3/§5)。决策记录见 [docs/adr/0012](docs/adr/0012-explicit-api-registration.md)(Accepted;SPI 与显式双轨共存被 One-Version 否决;失去平台被动发现与先启兼容由形态 B depend 纪律承接),验收追加 ★59(显式注册面;首建于 7.2/7.5,持续验证 = `ThirdPartyModeRegistrationTest`/`SessionComponentContributionTest` 等),完整变更见 [24-v1.6修正案](24-v1.6修正案-API面显式注册模式与会话组件.md)(变更指示,历史存档)。

## v1.5 版本定位(REUSE_DIRTY 副本随会话解散销毁)

**复用窗口 = 会话生命周期**——REUSE_DIRTY 地图的世界副本目录,在其所属会话解散后**全部销毁**(A 项新特性;代码已随 v1.5 施工窗口完成,git 实况为准)。v1.4 及之前的口径:REUSE_DIRTY 归零(切换离开)目录保留,复用理由「同会话复载」——但理由随会话死亡失效,目录从缓存变残留、随会话代际**线性累积**;本版把窗口关闭点定为解散:会话解散时端口经 `WorldPort#discardSession(SessionId)`(端口新增第三法,**engine 内部口,不在 MiniGameApi 冻结面**)销毁其名下全部副本,三类全覆盖:①计数表该会话的**存活条目**(摘条目 → 卸载不存档 + 删目录,策略无关);②**曾切换离开而保留在磁盘的目录**(端口新增「会话曾发放 mapId 集」簿记,按精确名 `mapId-短码` 删除);③**迟到 in-flight**(短码记入「已死短码集」→ 迟到的归零处置无论策略一律删)。**边界不变(B 项,零触碰)**:切换路径(B5 换图)对 REUSE_DIRTY 仍保留目录(窗口内复用语义原样);`TEMPLATE_RESTORE` 行为原样;删除仍走 `deleteCopyDir` 唯一入口 + 路径形态断言;卸载/删除失败 best-effort 保留 + WARN/SEVERE;公共面零改动——`API_VERSION` 维持 4、★19/★44 冻结快照零重录;硬崩溃残留(上一进程生命周期的孤儿)不在本次范围,登记 23 §6-Q1。决策记录见 [docs/adr/0011](docs/adr/0011-reuse-dirty-copy-dispose-on-dissolve.md)(Accepted;启动清扫 / 后缀匹配两备选否决——短码含 `-` 使后缀猜测会误删他 session 目录),验收追加 ★58(解散销毁;首建于 2.4,持续验证 = `WorldRefCountTest` ⑧ 组),完整变更见 [23-v1.5修正案](23-v1.5修正案-REUSE_DIRTY副本随会话解散销毁.md)(变更指示,历史存档)。

## v1.4 版本定位(API 面收敛与地图注册单通道)

**两项收缩把「同一能力只有一份口径」落到公共 API 面(MiniGameApi)与地图注册通道**:**A. API 面收敛**——两参 `createSession(String, String)` 重载删除(经用户裁决,破坏性变更),`createSession(SessionRecipe)` 成为公共 API 建会话**唯一形态**(`SessionRecipe` 是两参形态的严格超集,迁移口径 = 两参调用一律改 `SessionRecipe.of(modeId, mapId)`;冻结面 16 → **15 方法** = 查询 3 + 控制 1 + 成员 2 + 订阅 1 + 能力 2 + 建会话 1 + 地图 2 + 目录 3),`API_VERSION` 3 → **4**(消费方 `onEnable` 首行版本拒载纪律 14.36 不变;★19/★44 冻结快照随 v4 面合法重录,背书 = 22 §3/§5)。**B. 地图注册单通道**——「配置文件声明地图」特性退场:`maps/*.yml` 启动扫描装载、`YamlCodec.readMap`、`MapDefinition.from`、`MapRegistry.replace` 与文件半边、无参构造全部删除(迁移口径:原声明改由宿主/玩法插件代码构造 `MapDefinition` 后 `registerMap`);配置装载不再产生任何地图条目,地图**仅经** `registerMap` 进目录,`MapRegistry` 目录顺序 = 注册序;模板目录约定 `maps/templates/<mapId>/`、注册期校验(响亮抛)与「有会话正用 → 摘除被拒」守卫(20-D2~D4)**一寸不动**;宿主"reload" = `unregisterMap` + `registerMap`(原 `maps` 级 reload 的文件半边随通道退场)。完整变更见 [22-v1.4修正案](22-v1.4修正案-API面收敛与地图注册单通道.md)(变更指示,历史存档),决策记录见 [docs/adr/0010](docs/adr/0010-api-surface-consolidation.md)(Supersedes [0008](docs/adr/0008-map-registration.md) 的文件式通道部分),映射见 00 §0.5.10。

## v1.3 版本定位(框架基础设施瘦身与配置中立化)

**框架不得与任何一个小游戏的实现相关**(用户裁决)——三项收缩把该哲学推到基础设施与发行面:**R1 持久化整体出框架**:L5 `data/**`、HikariCP/mysql 依赖、`engine/port/PersistencePort`(连接口一起删——今日零消费者,属假想接缝,判据与 A13/A9 同源)、config `database:` 块与 `engine.shutdown-flush-budget-seconds` 全部移除;持久化归宿主线(宿主自建,经自己的组件/服务直连自己的 DB 栈;`StatsComponent` 采集与 `StatsDelta` 出口留框架,落库归宿主)。**R2 表现层出框架**:`component/display/**`、`render/**` 与 config `display:` 块移除;`DisplayModel` 数据驱动设计资产迁 [Shdf具体玩法设计案/10-表现层组件](Shdf具体玩法设计案/10-表现层组件.md) 保全(不销毁);施工子阶段 4.3 撤销;P3 组件计数 L3 实数 21→20。**R3 发行配置中立化**:`session.initial-mode`/`fallback-mode` 默认改空+注释引导、`arenas` 示例注释化、L0 缺省兜底改空串;空值语义 = 初始模式未注册 → WARN + 会话保留待后补切换、fallback 空/未注册 → 回退链跳过一级直接二级 dissolve;`config-version` 2→3;A3 改「回退去向 = 配置指向的任意已注册模式,框架不设出厂模式」。`performance.component-budget-ms` 保留(v1.1 信任边界观测,非玩法)。**同日 D5 增补:启动建会话特性删除、会话创建权移交宿主**——`HydroKarbsAssembly` 整段拓扑装配废止,框架 onEnable 零会话(零会话起服为合法状态);配置键 `session.topology`/`session.arenas`/`session.initial-mode`/`session.dissolve-after-empty-seconds` 删除(`session.fallback-mode`/`min-dwell-ticks`/`disconnect-grace-seconds` 三节律键保留);拓扑 = 宿主调几次 `SessionService.create(SessionSpec)`(§3.2 冻结签名不动),`SessionSpec.initialModeId` 由宿主填(每会话首切换目标),启动期不再发生任何切换。验收零新增编号:★10~★12(数据库三验收)归属改标「宿主线(框架侧 v1.3 废止)」;架构断言族(★1~★9/★13~★55)零触碰、ArchUnit 零放宽。完整变更见 [21-v1.3修正案](21-v1.3修正案-框架基础设施瘦身与配置中立化.md)(变更指示,历史存档),决策记录见 [docs/adr/0009](docs/adr/0009-framework-infrastructure-slimming.md),映射见 00 §0.5.9。

## v1.2 版本定位(地图注册通道)

**宿主/伴随插件经 `MiniGameApi` 显式注册与摘除地图**(`registerMap(Plugin, MapDefinition)` / `unregisterMap(Plugin, String)`;API_VERSION 2 → 3):补齐 v1.1 形态 B 下"玩法图随玩法 jar 分发、框架无从发现"的缺口,并使临时/活动地图(赛程器动态开图)成为一等公民。**地图交成品不交工厂**——`MapDefinition` 是纯静态值(无行为/无依赖图/无配置注入/无 reload 重建),直接注册建好的实例是安全的,与模式必须交 `MatchModeProvider` 工厂相对。注册期校验(id 查重与文件式同一命名空间、tags、**模板目录存在性**);卸载走显式 + `PluginDisableEvent` 兜底,**有会话正用 → 摘除被拒**(镜像 `/sm reload maps` 纪律);注册式地图不经 reload;文件装载与注册汇入 `MapRegistry` 同一私有 `register()`。**`WorldPort` 世界生命周期一寸不动**——注册交付的只是声明。完整变更见 [20-v1.2修正案](20-v1.2修正案-地图注册通道.md)(变更指示,历史存档),决策记录见 [docs/adr/0008](docs/adr/0008-map-registration.md),映射见 00 §0.5.8。

## v1.1 版本定位(框架独立插件化拆分与 API 面扩充)

**框架(HydroKarbs)拆为独立插件,玩法(宿主)成为消费插件**(`depend: [HydroKarbs]`),经扩充后的 `MiniGameApi`(API_VERSION = 2)对外提供平台服务:①成员操作(`joinSession`/`leaveSession`,D2);②会话事件订阅(`subscribeSessionEvents`,不可变快照族 `SessionEventView`,D3);③能力标签提供方注册(D4);④**会话组件贡献 SPI(D14)**——插件(宿主/伴随)经 `SessionComponentProvider`(全局默认)或 `SessionRecipe`(单次)两条通道"拼"会话,**模式仍无权、无运行期增删**。API 编译面以**白名单冻结**(`ApiSurfaceTest`,★44;不搬家,`SessionService`/`WorldPort`/`ServiceRegistry` 族等内部面永不导出),消费方查 `API_VERSION`、禁 shade(14.35/14.36)。**引擎不变式零改动**(§16.2 ★1~★40 原样,ArchUnit 断言零放宽);**服务不经 `MiniGameApi`**(三通道不变,D10;14.37)。交付走**两道闸门**:**G1**(API 面扩充 + 单 jar 彩排,★41~★46/★51~★53)→ **G2**(物理拆分,触发条件 = 首个真实跨 jar 消费者,A13,★47~★50;★50 断言双形态等价)。包根同步更名:框架 `com.shdf.match` → `com.hydrokarbs`(D13,搭 API_VERSION 1→2 破坏性窗口一次付清),宿主另立独立顶级包 `com.shdf.*`(旧根不复用)。完整变更见 [19-v1.1修正案](19-v1.1修正案-框架独立插件化拆分与API面扩充.md)(变更指示,历史存档),决策记录见 [docs/adr/0007](docs/adr/0007-standalone-framework-plugin-split.md),映射见 00 §0.5.7。

## v1.0 定位(纯框架)

**HydroKarbs MiniGame Framework(v1.0)是纯框架设计案**:自前代设计案 v4.1(旧代号见 17/18 与 docs/adr/ 历史存档)剥离全部具体玩法重构而成——只含框架架构(会话层 / 模式声明层 / 组件体系 / 进程级服务 / 切换与交接 / 世界 / 持久化 / 公共 API / 路线图与验收),不含任何具体玩法的设计、实现、配置或验收细节。原 04/05/08/09 四篇与玩法侧施工子阶段(2.5、5.1/5.2、7.3、阶段3、阶段6)已整体迁入 [Shdf具体玩法设计案/](Shdf具体玩法设计案/)——大厅/四模式的实现见该目录。装配类名相应改名:SHDFMatchAssembly → HydroKarbsAssembly、SHDFMatchPlugin → HydroKarbsPlugin;框架无宿主工程。以下 v4.x / v3.x「版本定位」各段为剥离前的历史记录,按原样保留(旧代名为历史存档名,见 17/18 与 docs/adr/)。

## v4.1 版本定位(相对 v4.0 的一处扩建:进程级服务层)

**进程级服务层:服务(Service)是与组件并列的另一类对象类别**——不挂进会话/实例(进程生命周期,`onEnable` 装配 → `onDisable` 关停)、不进组件表,经 `ServiceRegistry` 两阶段装配(`install` 声明 → `seal` 依赖校验 + 拓扑序创建 + 启动),经**订阅会话事件、只读快照、`SessionService` 受控命令**三条通道触达会话,与组件**互不可见**(双向禁止 import)。完整变更见 18(变更指示,非设计章节),决策记录见 ADR-0006(docs/adr/0006-process-service-registry.md;修正案原文编号 0005,因 docs/adr/0005 已被《架构冻结测试门禁》占用,顺延为 0006)。

## v4.0 版本定位(相对 v3.2 的一处扩建:会话级组件层)

**组件从"单一挂载级"扩为两级挂载**:实例级 `MatchModeComponent`(原样,随实例 `close()` 全灭)+ **会话级 `SessionComponent`**(新增,随会话 `create` → `dissolve`,**每会话一份**、跨模式切换存活);两级互相只读查询,对对方的干预只走端口。驱动来自三处:①模式要接入会话级能力(`SessionView` 只读查询 + 能力标签硬依赖声明);②会话行为要可插拔(空场解散策略自 `SessionPolicy` 下放为 `component/session/EmptyArenaComponent`,rotation 不挂 = 永不解散);③系统级要读实例运行数据(`MatchModeInstanceAccess` 只读查询当前实例)。**"单例"语义的修正**:会话级组件在会话生命周期内单例(同一会话内 `Specification#create` 至多一次,此后一切查询恒返回同一实例),**跨会话不共享**——原设想"进程级单例"在 arena 拓扑(N 个会话并发跑同一模板)下会串台,故修正为"每会话一份";共享的只能是不可变声明。完整映射见 00 §0.5.5,决策记录见 docs/adr/0004,变更指示见 17(非设计章节)。

## v3.2 版本定位(相对 v3.1 的一处下放)

**JoinPolicy 下放为组件**:`MatchModeMeta.joinPolicy`(OPEN/SPECTATE/CLOSED)字段删除;入场裁决改由 L3 组件 `JoinControlComponent`(§6.1-#24)实现引擎新端口 `JoinAdmission`(§3.2.1)——入场路由是行为,静态枚举写死策略名字与"行为全在组件"相悖。不挂 = 默认全接纳,大厅零配置。完整映射见 00 §0.5.4,决策记录见 docs/adr/0003。

## v3.1 版本定位(相对 v3 的两个收尾,完整映射见 00 §0.5.3)

1. **命名去歧义**:Mode 家族类型全部加 `Match` 前缀(`MatchMode` / `MatchModeMeta` / `MatchModeComponent` / `MatchModeInstance` / `MatchModeProvider` / `MatchModeRegistry` / `MatchModeSelection` / `MatchModeConfig` / `MatchModeInfo`)——裸名 "Mode" 在 Bukkit 语境与 `org.bukkit.GameMode` 持续撞名。方法名(`switchMode`)与事件名(`SessionModeSwitched`)无歧义,不改。
2. **框架内容盲收尾**:清除引擎中残留的一切内容词汇——删除 `HandoffKeys` 框架保留键(键随组件主人)、`EndResult` 瘦身为仅携带去向(结局词汇 `MatchOutcome` 下放 L3 endgate)、`Capability{ROLE}` 字符串化、删除切换 B3 角色兜底(引擎不碰桥接)、`MatchModeInstanceEvent` 内容词汇下放组件包、删除 `RolePort`/`DisplayPort`、引擎配置块去内容参数。

## v3.0 版本定位(相对 v2 的三个推到底)

1. **模式聚合根零行为**:`MatchMode` 只有 id/名称/描述/图标/标签/MatchModeMeta + 一张有序组件描述符表;v2 的 `plan()`/`rolePolicy()`/`transition()`/`validateMap()` 全部拆除。形状逐字段对照角色系统 `core/Role`(已验证的参照物,非猜想)。
2. **引擎不提供玩法级状态机**:阶段推进(`PhaseComponent`)、结束判定(`EndGateComponent`)、声明式规则(`RuleBookComponent`)从引擎整体降级为 L3 组件;引擎只做装配 / 派发 / 供端口(`end()` / `switchTo()`)/ 回收。
3. **角色桥接之上封装角色组件**:`RoleComponent`(§9)收拢选角授权/数值介入/敌对口径/采样调度/局末释放,模式只挂这一个组件,不再接触桥接层。**(SHDF 扩展线,v1.0 起移出框架基线;详见 [Shdf具体玩法设计案/](Shdf具体玩法设计案/)08、09)**

v2 引入并保留的:会话层与 `switchMode`(§3)、大厅模式化(原 §4,已迁玩法线,见 [Shdf具体玩法设计案/](Shdf具体玩法设计案/))、`Handoff` 类型化交接、`WorldPort` 世界实例化、`MiniGameApi` 公共面、角色系统软依赖(SHDF 扩展线,见 [Shdf具体玩法设计案/](Shdf具体玩法设计案/);v1.0 起移出框架基线)。

## 阅读路径

| 读者 | 路径 |
|---|---|
| **实现者(第一天)** | 00 → 01 → 02 → 16.4(一页速查);若按 v4 施工,先读 17(变更指示书,历史存档);若按 v4.1 施工,先读 18(变更指示,历史存档);若按 v1.1 施工,先读 19(修正案,历史存档);若按 v1.2 施工,先读 20(修正案,历史存档);若按 v1.3 施工,先读 21(修正案,历史存档);若按 v1.4 施工,先读 22(修正案,历史存档);若按 v1.5 施工,先读 23(修正案,历史存档);若按 v1.6 施工,先读 24(修正案,历史存档);**若按 v1.7 施工,先读 25(修正案,现行)** |
| **实现者(动工前)** | 03 → 12 → 06 → 14(陷阱必须通读) |
| **模式/玩法作者** | 02 → 13(新增模式的扩展步骤,框架口径);大厅/四模式等具体玩法的实现见 [Shdf具体玩法设计案/](Shdf具体玩法设计案/) |
| **服主/运维** | 15.2(配置)→ 10.2(数据存放)→ 16.1(路线图) |
| **第三方插件作者** | 07 → 13.4 |

## 文档索引

| 文件 | 内容 | 版本变化(v3 起) |
|---|---|---|
| [00-假设与体检](00-假设与体检.md) | 前置假设 A1~A13、对既有工程的体检结论、**概念迁移表(v1→v2、v2→v3、v3.2→v4)** | ★A8 聚合根零行为、A9 MatchModeProvider;新增角色系统纪律三条体检;★v4 追加 A10/A11 与 §0.5.5;★v4.1 追加 A12 与 §0.5.6;★v1.1 追加 A13 与 §0.5.7;★v1.2 追加 §0.5.8;★v1.3:A3 回退表述改注、A4 降级归宿主线注、追加 §0.5.9;★v1.4 追加 §0.5.10;★v1.5 追加 §0.5.11(23 号);★v1.6 追加 §0.5.12(24 号) |
| [01-分层架构](01-分层架构.md) | L0~L5 分层、依赖方向、与旧架构对照(bridge/role 层标注为宿主扩展示例,非框架组成) | 引擎盒子瘦身(无 phase/rule);对照表加 v3 三行;★v4.1 分层图加进程级服务层窄带、职责表与对照表各加一行 |
| [02-核心概念](02-核心概念.md) | **MatchMode 聚合根 / MatchModeComponent / MatchModeInstanceServices / 依赖校验 / 四条设计决断** | ★ 全章重写(v3 心脏);★v4:决断一重写、决断三扩展、新增决断四,新增 §2.5.1 会话级组件;★v4.1 新增 §2.9 进程级服务、决断一扩为三问、新增决断五 |
| [03-会话与模式切换](03-会话与模式切换.md) | Session 生命周期、`switchMode` 两阶段算法、Handoff、切换防护、部署拓扑、WorldPort | 准备/执行阶段对齐聚合根;TransitionPolicy→MatchModeMeta 数据;★v4.1 新增 §3.9 进程级服务层、§3.1 事件行补服务订阅者注、切换事件术语勘误;★v1.1:§3.9 通道③ D10 注与 §3.6 入口编排注 |
| [04-大厅模式](Shdf具体玩法设计案/04-大厅模式.md) | **已迁出(玩法线)**→ Shdf具体玩法设计案/;大厅模式的实现见该目录 | 迁出(v1.0) |
| [05-四模式矩阵](Shdf具体玩法设计案/05-四模式矩阵.md) | **已迁出(玩法线)**→ Shdf具体玩法设计案/;四模式差异矩阵见该目录 | 迁出(v1.0) |
| [06-共享组件](06-共享组件.md) | 框架共享组件清单(机制/依赖/挂载口径)、InteractionComponent;★v1.3:#12 DisplayComponent 与 §6.2 表现层设计整体迁 [Shdf具体玩法设计案/10-表现层组件](Shdf具体玩法设计案/10-表现层组件.md)(L3 实数 21→20);模式侧使用矩阵随玩法线见 Shdf具体玩法设计案/05 | ★ 新增 Phase/EndGate/RuleBook 三行;★v3.2 增 #24 JoinControlComponent;★v4.1 补候选复核判据(D10)与基础设施消费尾注;角色组件行(SHDF 扩展线)迁 Shdf 侧 08/09;★v1.3 display 迁出 |
| [07-公共API](07-公共API.md) | `MiniGameApi` 冻结面、MatchModeProvider 注册、第三方扩展边界 | ★ 注册面改为工厂接口;A9 不可变限制;★v4.1 新增 §7.4 进程级服务的 API 边界;★v1.1:§7.1 升 v2(API_VERSION/成员/事件/能力/D14 增量)、§7.2 加三行、新增 §7.5 跨插件消费;★v1.2:§7.1 升 v3(`registerMap`/`unregisterMap`)、§7.5 加地图注册行;★v1.4:§7.1 升 v4(删两参 createSession,建会话唯一形态 `SessionRecipe`,API_VERSION=4,冻结面 15 方法);★v1.6:§7.1 升 v5(API_VERSION=5、冻结面 19 法——显式注册四法,ServicesManager 提供方通道退场,`SessionComponentProvider` SPI 删除) |
| [08-角色桥接](Shdf具体玩法设计案/08-角色桥接.md) | **已迁出(玩法线/SHDF 扩展线)**→ Shdf具体玩法设计案/;角色系统接入见该目录 08、09 | 迁出(v1.0) |
| [09-角色组件层](Shdf具体玩法设计案/09-角色组件层.md) | **已迁出(玩法线/SHDF 扩展线)**→ Shdf具体玩法设计案/09 | 迁出(v1.0) |
| [10-数据库](10-数据库.md) | DDL、数据存放划分、虚拟线程访问层、HikariCP、线程边界(**★v1.3 分割:§10.1~§10.4 迁宿主线存档保全;§10.5 Threads 与线程边界保留为框架基线**) | v2 原样(仅章节号 9→10);★v1.3 头部分割注 |
| [11-事件流](11-事件流.md) | **模式切换时序图**、解耦要点表、**会话级组件生命周期时序图(★v4)**、服务生命周期时序图(★v4.1);玩法侧时序图随玩法线迁 Shdf具体玩法设计案/ | 切换图对齐 createInstance/awakeAll/startAll;★v4 新增 §11.4 与切换间隙注;★v4.1 新增服务生命周期时序图、解耦要点表补行 |
| [12-代码骨架](12-代码骨架.md) | 包结构、MatchModeInstance 容器、MatchModeRegistry、plugin.yml、gradle;玩法工厂示例随玩法线迁 Shdf具体玩法设计案/ | ★ 全章重写(引擎少两个包,模式全变工厂);★v4.1 新增服务契约骨架与 L0 装配(HydroKarbsAssembly);★v1.1:包根更名 `com.hydrokarbs`(D13)、追加形态 B 双工程树、api/ 增量与 `requiredApiVersion()`;★v1.2:MapRegistry 双通道注、测试树 `MapRegistrationTest`、§12.2 v1.2 增量注;★v1.4:MapRegistry 单通道化(maps/\*.yml 装载链删除)、api/ v4 注、测试树 `MapTestKit`、§12.2 v1.4 收敛注;★v1.6:api/ 行注(四法显式注册,`SessionComponentProvider` 删除)、`MatchModeRegistry` 双发现收敛注(ServicesManager 半边退场)、§12.2 ★v1.6 收敛注(19 方法) |
| [13-扩展步骤](13-扩展步骤.md) | 新增 CTF 的步骤表、换大厅验收、跨插件注册、引擎级升级路径 | ★ CTF 改 Builder 声明(~行数下降);★v4.1 新增进程级服务步骤表与常驻模块选型;★v1.1:§13.4 加 API_VERSION 检查、§13.9 案②升级、新增 §13.10 跨 jar 消费三例;★v1.2:新增 §13.11 跨 jar 注册地图;★v1.4:§13.11 文件式对比段改历史口径(地图单通道,原 maps/\*.yml 声明改代码构造注册);★v1.6:§13.4 步骤表重写(depend + load 后显式登记四法,先启补收录退役)、§13.10 例三改 `registerSessionComponents` |
| [14-陷阱清单](14-陷阱清单.md) | 39 条陷阱(线程/DB/切换/组件纯度/跨级纪律/跨 jar 消费) | ★ 新增 14.23~14.26(聚合根长行为/引擎回流状态机/svc 膨胀/裸写状态);★v4 追加 14.27~14.30、修订 14.7;★v4.1 追加 14.31~14.34、修订 14.29;★v1.1 追加 14.35~14.38(shade/API_VERSION/服务击穿三通道/句柄残留);★v1.2 追加 14.39(注册地图模板落盘时序);★v1.6:14.38 兜底面扩四族注(能力/模式/地图/贡献;零新增编号,24 号) |
| [15-PaperMC适配](15-PaperMC适配.md) | 事件优先级表、配置组织、三流水线调度、虚拟线程 | v2 原样(仅章节号 14→15);★v4.1 三流水线补服务节拍注、不新增配置键;★v1.1:形态 B 配置分工注、新键 `performance.component-budget-ms`(唯一新增);★v1.2:§15.2 reload 分级注(注册式地图不经 reload)与模板落盘口径注;★v1.4:maps/\*.yml 声明通道删除注(YamlCodec 只余 modes 解析),reload 分级注同步 |
| [16-路线图与验收](16-路线图与验收.md) | P0~P8 阶段表(含 ★v4 的 P1.5)、**60 条机械验收(含 ★34~★40 与 ★41~★60;★ 编号体系框架/玩法两线同一套,玩法线条目随文档迁 Shdf 侧;★v1.3:★10~★12 归属改标「宿主线(框架侧 v1.3 废止)」,★v1.4 追加 ★56~★57,★v1.5 追加 ★58(23 号),★v1.6 追加 ★59(24 号),★v1.7:★32 归属就地注「框架侧 v1.7 废止」+ 追加 ★60(25 号),计数口径 = 框架 53 + 扩展线 3 + 联合 1(★32,存档)+ 宿主线 3)**、一页速查;RoleAPI 对照表已迁玩法线(见 Shdf具体玩法设计案/) | ★ 新增验收 22~26;路线图加 P0.5 三迁组件;★v4 加 P1.5 与 ★27~★33;★v4.1 加 P0.6 与 ★34~★40;★v1.1 加 P7.6(G1)/P8.5(G2) 与 ★41~★53;★v1.2 加 ★54~★55(地图注册);★v1.3 零新增编号(收缩);★v1.4 加 ★56~★57(API 面收敛/地图单通道);★v1.5 加 ★58(解散销毁,23 号);★v1.6 加 ★59(显式注册面,24 号);★v1.7:★32 就地废止注/★21 就地注 + 加 ★60(类型化桥接,25 号) |
| [17-v4变更指示书](17-v4变更指示书.md) | **变更指示(非设计章节,历史存档)**:v4.0 会话级组件层——D1~D11 决策、契约骨架、逐文档修改指示、验收 ★27~★33 | ★v4 新增 |
| [18-v4.1修正案](18-v4.1修正案-进程级服务层与ServiceRegistry.md) | **变更指示(非设计章节,历史存档)**:v4.1 进程级服务层与 ServiceRegistry——D1~D12 决策、契约骨架、逐文档修改指示、验收 ★34~★40 | ★v4.1 新增 |
| [19-v1.1修正案](19-v1.1修正案-框架独立插件化拆分与API面扩充.md) | **变更指示(非设计章节,历史存档)**:v1.1 框架独立插件化拆分与 API 面扩充——D1~D14 决策、契约骨架、逐文档修改指示、验收 ★41~★53(D14 扩编至 ★53) | ★v1.1 新增 |
| [20-v1.2修正案](20-v1.2修正案-地图注册通道.md) | **变更指示(历史存档,非现行;现行修正案见 25)**:v1.2 地图注册通道——D1~D7 决策、契约骨架、逐文档修改指示、验收 ★54~★55 | ★v1.2 新增 |
| [21-v1.3修正案](21-v1.3修正案-框架基础设施瘦身与配置中立化.md) | **变更指示(历史存档,非现行;现行修正案见 25)**:v1.3 框架基础设施瘦身与配置中立化——D1~D4 决策(持久化出框架/表现层出框架/发行配置中立化/信任边界键)、配置 diff、逐文档修改指示、验收处置(★10~★12 归属重标注,零新增)、悬置项与回填义务 | ★v1.3 新增 |
| [22-v1.4修正案](22-v1.4修正案-API面收敛与地图注册单通道.md) | **变更指示(历史存档,非现行;现行修正案见 25)**:v1.4 API 面收敛与地图注册单通道——D1~D4 决策(两参 createSession 删除、API_VERSION 3→4、文件式地图通道退场、MapRegistry 单通道化)、契约骨架(v4 面 15 方法,以现行 MiniGameApi.java 实签转写)、逐文档修改指示、验收追加 ★56~★57 | ★v1.4 新增 |
| [23-v1.5修正案](23-v1.5修正案-REUSE_DIRTY副本随会话解散销毁.md) | **变更指示(历史存档,非现行;现行修正案见 25;★v1.6 状态注已加,仅头部)**:v1.5 REUSE_DIRTY 副本随会话解散销毁——D1~D3 决策(复用窗口 = 会话生命周期:discardSession 三类覆盖;切换路径语义不变;公共面零改动)、契约骨架(WorldPort 三法,以现行 WorldPort.java 实签转写)、逐文档修改指示、验收追加 ★58 | ★v1.5 新增 |
| [24-v1.6修正案](24-v1.6修正案-API面显式注册模式与会话组件.md) | **变更指示(历史存档,非现行;现行修正案见 25;★v1.7 状态注已加,仅头部)**:v1.6 API 面显式注册模式与会话组件——D1~D4 决策(显式注册四法与响亮收录闸;SPI 提供方通道退场与依赖反转——SessionComponentProvider/ProviderServiceListener 删除、MatchModeProvider 转载荷、ServiceLoader 本 jar 半边保留、先启补收录退役;disable 兜底四族与摘除回退语义原样迁移;API_VERSION 4→5)、契约骨架(v5 面 19 法,以现行 MiniGameApi.java 实签转写)、逐文档修改指示(含主编/编辑执行主体分区)、验收追加 ★59 | ★v1.6 新增 |
| [25-v1.7修正案](25-v1.7修正案-能力标签机制退场与会话组件桥接.md) | **变更指示(现行,非历史存档)**:v1.7 能力标签机制退场与会话组件桥接——D1~D4 决策(标签机制全链退场:两法/CapabilityRegistry/A2 校验步/meta 与快照分量/两端声明/兜底能力块;外部能力 = 会话组件桥接:类型化 requires/requiresOptional、装配期响亮/运行期 quarantine、桥接由提供方或宿主自持;校验时机位移:每切换重查 → 创建时绑定 + 运行期降级;API_VERSION 5→6)、契约骨架(v6 面 17 法,以现行 MiniGameApi.java 实签转写)、逐文档修改指示(含主编/编辑执行主体分区)、验收处置与追加(★32 就地废止注/★21 就地注 + ★60) | ★v1.7 新增 |
| [docs/adr/0010](docs/adr/0010-api-surface-consolidation.md) | **决策记录(Accepted)**:API 面收敛与地图注册单通道——两项决策的动机、备选(弃用过渡期 vs 直接删除、薄便利方法 vs 删除、保留文件通道 vs 单通道等)与权衡;Supersedes 0008 的文件式通道部分 | ★v1.4 新增 |
| [docs/adr/0011](docs/adr/0011-reuse-dirty-copy-dispose-on-dissolve.md) | **决策记录(Accepted)**:REUSE_DIRTY 副本随会话解散销毁——动机(孤儿磁盘残留线性累积)、备选(启动清扫 vs 解散钩子;后缀匹配 vs 精确追踪——短码含 `-` 会误删他 session 目录)与权衡 | ★v1.5 新增 |
| [docs/adr/0012](docs/adr/0012-explicit-api-registration.md) | **决策记录(Accepted)**:API 面显式注册模式与会话组件——动机(注册点显式可审计/摘除路径与 registerMap 同构/事件时序复杂度退役)、备选(SPI 与显式双轨共存被 One-Version 否决;仅加法不删 SPI)、权衡(失去平台被动发现与先启兼容——由形态 B depend 纪律承接);Supersedes 0007 的 SessionComponentProvider SPI 通道部分 | ★v1.6 新增 |
| [docs/adr/0013](docs/adr/0013-capability-tags-removal.md) | **决策记录(Accepted)**:能力标签机制退场,外部能力改会话组件桥接——动机(字符串契约不严谨/撞名与拼写错误的运行期才暴露/与组件依赖图平行的弱机制)、备选(强类型枚举——历史已废止一次、封闭集不可跨插件扩展,否决;框架定义类型化端口——内容词汇进 engine 触红线,否决;双轨保留——One-Version 否决)、权衡(失去零编译耦合与每切换重查的进程级新鲜度——由宿主自持桥接与组件降级承接);Supersedes 0007 的能力标签提供方注册(D4)部分 | ★v1.7 新增 |
| [施工计划/](施工计划/README.md) | 施工总计划(框架线八阶段,含 v1.1 新增阶段8;阶段3、阶段6 与 2.5、5.1/5.2、7.3 等玩法线子阶段已迁 [Shdf具体玩法设计案/施工计划/](Shdf具体玩法设计案/施工计划/)) | — |
| [Shdf具体玩法设计案/](Shdf具体玩法设计案/) | **玩法线目录**:大厅(04)、四模式矩阵(05)、角色桥接/角色组件(08/09)与玩法侧施工计划——具体玩法的实现、配置与验收全部在此 | 迁入(v1.0) |

## 硬边界(速记,详见 16.2)

- `engine/**` 不 import `mode/**`、`component/**`、`bridge/**`;源码不含任何 mode id 字面量(含 `lobby`),不含 `Phase`/`Rule`/`EndCondition`/`Role`/`Team`/`Score` 等内容词汇;引擎不写任何 Handoff 键
- `MatchMode` 聚合根公开方法集被 `AggregatePurityTest` 反射冻结;`MatchModeInstanceServices` 成员封闭(3 个)
- **(SHDF 扩展线,非框架组成)**桥接层(`bridge/role/**`)只被 `RoleComponent` 消费(角色事件词汇也住 `component/role`);模式与其他组件只见 `RoleComponent` 读口——该层整体属 SHDF 宿主扩展,详见 [Shdf具体玩法设计案/](Shdf具体玩法设计案/)08、09,v1.0 起不在框架基线内
- 主线程单 tick 原子切换;实例状态随 `close()` 全灭;跨模式数据只走 `Handoff` 或寄居会话级组件;会话组件状态随 `dissolve` 全灭
- 跨级查询只读、干预只走端口(`end()`/`switchTo()` ↔ `requestSwitch()`/`requestDissolve()`)
- 会话组件不参与切换装配(切换前后其实例恒等)
- 进程级服务不进组件体系:经 ServiceRegistry 装配、与组件互不可见、三通道触达会话(§3.9)
- API 编译面白名单由 `ApiSurfaceTest` 冻结(★44):白名单公开签名只增不改,`SessionService`/`EngineTicker`/`WorldPort`/`ServiceRegistry` 族/`bridge/**` 等内部面永不导出;消费方 jar 内不得出现 `com.hydrokarbs.` 前缀类(禁 shade,14.35)。(★v1.3:反面词表中的 `PersistencePort`/`data/**` 为历史口径——该包已出框架基线;**★v1.4:两参 `createSession(String, String)` 重载删除为经 22 号修正案裁决的破坏性例外**——API_VERSION 3→4,建会话唯一形态 `createSession(SessionRecipe)`,重录后的 15 方法清单为新的「只增不改」基线;**★v1.6:`SessionComponentProvider` 公共接口删除为经 24 号修正案裁决的破坏性例外**——API_VERSION 4→5,模式/会话组件经四法显式注册(`registerMode`/`unregisterMode`/`registerSessionComponents`/`unregisterSessionComponents`),ServicesManager 提供方通道退场;**★v1.7:能力标签两法 `registerCapability`/`capabilities()` 删除为经 25 号修正案裁决的破坏性例外**——API_VERSION 5→6,外部能力 = 会话组件桥接,重录后的 17 方法清单为新的「只增不改」基线)
- **(★v1.3)框架不含持久化与表现层**:`data/**`、`render/**`、`component/display/**`、`bridge/persistence/**`、`engine/port/PersistencePort`、config `database:`/`display:` 块与 HikariCP/mysql 依赖均不在框架基线——持久化归宿主线自建、表现层设计见 [Shdf具体玩法设计案/10-表现层组件](Shdf具体玩法设计案/10-表现层组件.md);发行配置零玩法缺省(`fallback-mode` 默认空,框架不设出厂模式,21-D1~D3;**★v1.3-D5:`session.topology`/`arenas`/`initial-mode`/`dissolve-after-empty-seconds` 键一并删除——框架 onEnable 零会话,会话由宿主经 `SessionService.create(SessionSpec)` 创建,框架不预设「开局建几个会话、进哪个模式」**)
- 服务不经 `MiniGameApi`(三通道不变,D10;14.37 + ★40 增款:`engine/service/**` 与顶层 `service/**` 不得 import `api/**`)

---
