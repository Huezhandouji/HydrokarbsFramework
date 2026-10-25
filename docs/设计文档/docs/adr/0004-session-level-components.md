# ADR-0004:会话级组件层:两级挂载与跨级只读

- 状态:已接受(2026-10-17,v4.0;部分取代 ADR-0001 决策 4)
- 关联:ADR-0001(大厅即模式)、ADR-0002(聚合根零行为与引擎纯度)、ADR-0003(JoinPolicy 下放为组件);`设计案/` v4.0(§2.5.1、§2.8、§3.8);`设计案/17-v4变更指示书.md`(D1–D11)

## 背景(Context)

v3.2 的组件只有单一挂载级:实例级 `MatchModeComponent`,生命周期随 `MatchModeInstance`,`close()` 全灭。会话行为——名册、连接追踪、空场策略、世界句柄——全部内建在 `SessionService`/`SessionPolicy`。这正是 ADR-0001 决策 4 的结论;其备选方案 B("会话级 + 实例级两层组件")当年以"会话级行为总共四件事(名册/连接/空场/世界句柄),两层组件模型带来的复杂度远超收益"为由否决。

v4 出现三个驱动,使上述判断的前提不再成立:

1. **模式要接入会话级能力**:模式/实例组件需要读取并使用会话级组件提供的能力,并可声明硬依赖(能力标签)。
2. **会话行为要可插拔**:会话的*策略与行为*(如空场解散)要从内核下放为组件;加/换行为不改 `SessionService`。
3. **系统级要读实例运行数据**:会话级组件需要只读查询当前实例的组件数据(阶段/存活/计分等)。

关键变化在于:会话级行为必须能够读取实例数据、按会话装配、并可被模式声明依赖——"内建 + 数据配置"已表达不下(静态枚举与配置项无法承载"读实例、按会话裁决"的策略)。因此 v4 重新核算 ADR-0001 备选方案 B:复杂度成本真实存在(组件模型 +1 级),但以 D7 判据表、D9 隔离语义与 ★27~★33 冻结测试作为对冲后,收益首次超过成本。本 ADR 接受两层组件。

## 决策(Decision)

**"单例"语义的修正(每会话一份)**:会话级组件在**会话生命周期内**是单例——同一 Session 内 `Specification#create` 至多调用一次,此后一切查询(实例组件、会话组件互查、会话内核)恒返回同一实例;**跨会话不共享**。原设想"进程级单例"在 arena 拓扑(N 个会话并发跑同一模板)下会串台,故修正为**每会话一份**。共享的只能是不可变声明(Spec/Snapshot/SPEC 常量),有状态实例永不跨会话共享。

### D1 两级挂载

组件有两种挂载级,仅两种:

- **实例级 `MatchModeComponent`**(原样):生命周期随 `MatchModeInstance`,`close()` 全灭;由模式聚合根的组件描述符表声明。
- **会话级 `SessionComponent`**(新增):生命周期随 `Session`(create → dissolve),跨模式切换存活;由**会话装配**声明(见 D4)。

禁止第三级;禁止同一组件类兼任两级(同一行为若要跨级,拆成两个组件、经 D5 只读查询协作)。

### D2 会话级生命周期与钩子

钩子与实例级同名对齐,语义按会话重定义:

| 钩子 | 时机 | 语义 |
|---|---|---|
| `awake()` | 会话装配期,`create` 之后 | 只解析**会话级**依赖并缓存引用;幂等;无可见副作用 |
| `start()` | 会话创建完成、首个实例装配之前 | 生效:订阅会话/实例事件、登记显示、初始化会话级数据 |
| `update()` | 每 tick,**与"当前是否有实例"无关** | 会话策略推进(空场计时等)。禁止阻塞、禁止 IO |
| `onEvent(SessionEvent)` | 会话事件中央总线广播 | 与订阅二选一皆可;订阅随 `stop` 自动回收 |
| `stop()` | dissolve(或隔离)时 | 与 `start` 严格对称;返回后框架自动回收本组件登记的资源(订阅/延时任务/显示) |

派发规则与实例级一致:**按声明序**广播,`stop` **逆序**;`stop` 由框架**无条件**调用(无单例豁免条款——这正是"组件"与"内核机制"的分界,见 D7)。

> ★v4 落盘注(非 D 原文,待裁决):D2 表 `start()` 行的"订阅**实例事件**"在本版契约骨架中**无接口落点**——会话侧订阅面只有 `SessionPort.events()`(仅覆盖会话事件,§3/12 §12.2),`InstanceAccess`/`SessionView` 均无 `events()`。删该半句还是补订阅面,提请上游裁决;同登记见 `设计案/03-会话与模式切换.md` §3.8 与施工计划 2.6(未静默决定)。

### D3 装配与"每会话一份"

- 装配点唯一:`SessionService.create(sessionSpec)` 内,先 `verifyDependencies()`(缺会话级必需依赖 → 抛,会话创建失败,L0 SEVERE)再按声明序 `create(id, svc)`;`svc` 构造期注入,四条件照抄实例级(注入先于一切钩子/`final` 只注一次/未注入即抛/创建点唯一)。
- `SessionComponentRegistry.bind(id, snapshot)` 幂等:同会话重复绑定同 id 抛 `IllegalStateException`;id 跨类型去重(同一命名空间)。
- 每会话一份的机械保证:注册表挂在 `Session` 上,**不是进程级静态**。`SessionComponentSingletonTest` 冻结(同 Spec 装配两个会话 → 两个不同实例;同会话查询恒同一实例)。

> ★v4 落盘注(非 D 原文,待裁决):上条中"`bind` **幂等**"与"同会话重复绑定同 id **抛** `IllegalStateException`"语义相抵(幂等 = 重复无害 vs 重复即抛),实现者无法据以编码。实例级对照口径为"重复 register 视为编程错误、抛"(施工计划 1.3 步骤 4);本版照录原文未自改,口径澄清(如"同 id 同快照幂等去重、异快照抛",或"重复即抛"并删"幂等"字样)提请上游裁决;同登记见施工计划 2.6。

### D4 装配来源(第一版)

- `SessionSpec` 新增 `List<SessionComponent.Specification<?>> sessionComponents`(有序表 = 装配序 = 派发序),由 **L0 装配期按配置构建**(例:rotation 不挂空场组件;arena 挂 `new EmptyArenaComponent.Spec(Duration.ofSeconds(cfg))`)。
- **模式不得注册/增删会话级组件**(第一版;模式接入会话能力走 D6/D5 的"读 + 声明")。第三方会话组件 SPI 列为未决(§11-Q2)。
- 会话组件表**不参与模式切换**——切换的 A/B 两阶段与装配代码零改动(`SessionComponentSurvivesSwitchTest` 冻结:100 次切换后会话组件实例恒等、`update` 计数连续)。

### D5 跨级查询:互相只读,写只走端口

| 方向 | 通道 | 授权 |
|---|---|---|
| 实例组件 → 会话组件 | `svc().self().session()`(`InstancePort` 新增方法)→ `SessionView#component(Class<T extends SessionComponent>)` | **只读**;不得订阅会话事件(第一版,未决 §11-Q7) |
| 会话组件 → 实例组件 | `svc().self().currentInstance()` → `Optional<InstanceAccess>`;`InstanceAccess#component(Class<T extends MatchModeComponent>)` | **只读**;无实例或 `ENDING/SWITCHING` 间隙 → `empty`;**不得缓存实例组件引用越过切换**(现取不缓存) |
| 会话组件 → 实例干预 | 只有 `requestSwitch()` / `requestDissolve()` + 事件 | 等价于实例级 `end()`/`switchTo()` 的口径 |
| 实例组件 → 会话干预 | 只有原有 `end()`/`switchTo()`;会话级干预**不提供**(模式无权解散会话,第一版) | — |

- 查询类型 token 一律是**接口**(L3 组件类/引擎端口);引擎侧签名是泛型(`<T extends …Component>`),引擎不认识任何具体组件类——`EnginePurityTest` 不受影响。
- "只读"是**架构纪律 + review 条款**(无法机械强制调用方不碰写方法);写入 `14-陷阱清单` 与 code review checklist。

### D6 能力标签扩展(模式接入会话能力的硬依赖声明)

- `SessionComponent.Specification#capabilities(String...)`:会话组件在装配时注册能力标签(随 dissolve 摘除;词汇由提供方定义,引擎零字面量——沿用 v3.1 字符串标签口径)。
- `MatchModeMeta.requiredCapabilities` 语义扩展为"进程级标签 ∪ **本会话**会话组件标签";切换阶段 A2 校验,缺任一 → `REJECTED_BY_TARGET_MODE`,detail 列缺失标签(机制不变,只扩可用集来源)。
- 软硬两挡:软接入 = 实例组件直接 `session().component(X.class)` 判空;硬依赖 = `requiredCapabilities` 声明标签,装配期挡住。

### D7 内核 vs 组件:职责判据表

| 判定 | 归属 | 例 |
|---|---|---|
| 不需要跨模式存活 | 实例级组件 | 阶段/计分/队伍/结束判定 |
| 需要跨模式存活,且是**机制/状态权威/切换或交付算法的一部分/可靠性底线** | **`SessionService` 内核**(不组件化) | 名册数据结构与登记、成员连接态记录、会话状态机、switchMode 两阶段与三级回退、WorldPort 调用时点与引用计数、会话事件总线、dissolve 执行(传送/释放/摘除)、min-dwell 防护 |
| 需要跨模式存活,且是**策略/裁决/聚合/展示** | **会话级组件** | 空场解散策略(第一波)、断线宽限策略(候选)、跨局累计/系列赛(候选)、会话级看板(候选)、会话侧容量裁决(候选) |

- 争议仲裁原则:拿不准时问两问——"它坏了会话会失守底线(名册错/世界漏/切换不原子)吗?"(是 → 内核);"服务器主想要另一种做法吗?"(是 → 组件)。
- 内核向组件只提供端口与事件;组件向内核只经端口请求(D5 表)。

### D8 会话级出局口(端口落点)

`SessionPort` 提供两个行动口,镜像实例级:

```java
void requestSwitch(MatchModeSelection target, Handoff payload); // 与 switchMode 同一实现,受 min-dwell/重入约束
void requestDissolve(DissolveReason reason);                    // 幂等;仅会话级组件与内核可调(内核本就有)
```

`requestDissolve` 是本版**新增的唯一"组件影响力的面"**——空场策略组件的动作落点;不新增其他影响力端口。

### D9 故障隔离(会话级 quarantine)

- 会话组件在 `update/onEvent` 抛异常 → 记日志(会话 id / 组件 id / 原因)→ 对该组件 `safeStop` + 打标 quarantined(此后跳过其派发)→ 发 `SessionComponentQuarantined`(新增会话事件)→ **会话不解散**,其余组件照常。
- 与实例级 quarantine 的差异及理由:**实例级**隔离 → `end()` → 回退模式(模式坏了有兜底);**会话级**没有回退目标,解散代价过大——停用单个策略组件是可接受的降级(如空场策略停用 = 行为退回"永不解散"并留日志)。
- 装配期 `awake/start` 抛 → 会话创建失败(响亮,L0 SEVERE;arena 则该竞技场不启动)。

### D10 Handoff 边界修订

- 实例状态仍随 `close()` 全灭;**实例间**跨模式数据仍只走 `Handoff`(不变)。
- 新增合法通道:**跨切换存活的会话级状态可以住在会话级组件里**(这正是本版的目的),随 `dissolve` 全灭。
- README/16.2 硬边界句改为:"实例状态随 `close()` 全灭;跨模式数据只走 `Handoff` 或寄居会话级组件;会话组件状态随 `dissolve` 全灭"。

### D11 第一波迁移:空场策略下放

- `component/session/EmptyArenaComponent`:Spec 构造数据 `after`(空场持续时长);`start` 订阅 `MemberJoined/MemberLeft`,`update` 维护空场起点,到点 `svc().self().requestDissolve(DissolveReason.EMPTY)`;有成员回归即重置计时。
- 装配:L0 按 `session.topology` —— rotation **不挂**(常驻永不解散)、arena 挂(时长读 `session.dissolve-after-empty-seconds`,键保留,消费者变更为 L0)。(**★v1.3-D5 注(ADR-0009):两键随启动建会话特性删除——装配者改宿主(组装 `SessionSpec.sessionComponents` 时自供 `after`);本条「不挂 = 永不解散」的机制决策不变,仅装配主体与时长来源移宿主。**)
- `SessionPolicy` 中的空场判定/`idleTimers` 空场部分**删除**;连接追踪与宽限 sweep 留在内核(第二波候选,未决 §11-Q1)。
- "不挂 = 默认"原则延续:不挂任何空场组件 = 永不解散(大厅零配置哲学一致)。

## 备选方案(Considered)

- **方案 A:进程级单例会话组件**。否决:arena 拓扑下 N 个会话并发跑同一模板,单例状态跨会话串台;且 `SessionServices` 每会话一份、构造期一次性注入(`final`、只注一次、未注入即抛),进程级单例只能绑定首个会话的 svc,后续会话无法注入自己的服务集。故修正为每会话一份(D3)。
- **方案 B:放宽引擎分层,让引擎直接认识会话组件/模式实现**。否决:引擎内容盲失效——`EnginePurityTest` 禁词表与"引擎不 import `component/**`"是硬边界;引擎一旦认识具体组件,新增/更换会话行为就要改引擎,与驱动②直接冲突。
- **方案 C:维持全内建(会话行为继续写死在 `SessionPolicy`)**。否决:三驱动均不可达——模式无会话能力读口(①)、加/换行为必须改 `SessionService`(②)、系统级读不到实例数据(③)。

## 后果(Consequences)

- **正面**:三驱动全部达成——①模式经 `SessionView`/能力标签接入会话级能力;②空场策略成为可插拔组件,`SessionService` 不再因会话行为变更而修改;③系统级经 `InstanceAccess` 只读实例运行数据。复用切分多一层落点:跨模式复用的有状态逻辑首次有了正式居所,不必挤进 `Handoff` 或内核。
- **负面/成本**:组件模型复杂度 +1 级(两级挂载、两套注册/派发/隔离语义、跨级只读纪律)。对冲:D7 判据表(两问)+ D9 隔离语义 + 冻结测试 ★27~★33 + `14-陷阱清单` 14.27~14.30。
- **不可逆点**:术语与包结构(`SessionComponent`/`SessionServices`/`SessionPort`/`InstanceAccess`/`SessionView`/`SessionComponentRegistry`/`component/session/`)进入 ADR 后即成为公共词汇,再改即破坏性变更。机制/策略边界亦由冻结测试固化。
- **未决问题**:Q1——断线宽限策略是否下放为会话级组件(第二波),以及是否需要新增 `requestMemberRemoval` 端口;Q2——第三方会话组件 SPI 是否开放、何时(第一版不开放;另见决策 D4 与 `设计案/07-公共API.md` §7.3);登记于此,下一版评估。本版连接追踪与宽限 sweep 仍在内核(D11)。

### 与既有 ADR 的关系

| 既有决策 | v4 处理 |
|---|---|
| ADR-0001 决策 4"组件只挂实例;不引入会话级组件(避免组件模型翻倍)" | **被取代**。理由变化:当年会话行为只有四件(名册/连接/空场/世界句柄);v4 有三个新驱动(模式接入会话能力、会话行为可插拔、系统级读实例数据),且会话级行为必须能读取实例数据、按会话装配——内建 + 数据配置已表达不下 |
| ADR-0001 决策 3"Session 常驻容器(名册/连接/世界句柄)" | **修订**:名册/状态机/切换/世界机制仍在内核;空场策略等下放组件。决策 3 主体保留 |
| ADR-0001 备选方案 B"会话级 + 实例级两层组件"(当年否决) | **被接受**:当年否决理由"复杂度 vs 收益"在 v4 重新核算;以 D7 判据表 + D9 隔离 + 冻结测试作为复杂度的对冲 |
| ADR-0001 决策 1/2(大厅是普通模式、switchMode 一等公民) | 不动 |
| ADR-0002(聚合根零行为 + 引擎纯度) | 不动;引擎仍定义组件契约(现在是**三个契约**:`MatchModeComponent`、`MatchMode`、`SessionComponent`),仍不 import `component/**` |
| ADR-0003(JoinPolicy 下放为组件) | 不动;并被引用为"行为下放"先例——v4 是同一条路走到了会话层 |
