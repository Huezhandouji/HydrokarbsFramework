# ADR-0014:地图声明按路径直交与 MapDefinition 瘦身(v1.8)

> **状态注(2026-10-28,★ADR-0016)**:**Superseded in part by [ADR-0016](0016-attribute-bag-removal.md)**——本档案「决策 1」的声明形状(`(worldDirectoryPath, metaAttributes)` 两分量、`AttributeBag` 作词料载体)、「决策 3」的约定键口径(`map.tags`/`map.reset-strategy`)与「决策 4」的 `AttributeBag` 三口径随属性袋退场裁决退场;本档案其余结论——决策 2(声明按路径直交、`MapRegistry` 无模板根、注册校验直验路径)与决策 5(`API_VERSION` 6→7)——继续有效(下文按原文存档,不随本次裁决改写)。

- 状态:已接受(2026-10-28);2026-10-28 起**部分替代于 ADR-0016**(属性袋退场与三字段定案——见上方状态注)
- 关联:[26-v1.8修正案-地图注册去模板根约定与MapDefinition声明瘦身.md](../../26-v1.8修正案-地图注册去模板根约定与MapDefinition声明瘦身.md)(变更指示全文);判据与 [ADR-0010](0010-api-surface-consolidation.md)/[ADR-0012](0012-explicit-api-registration.md)(「同一能力只留一份口径」)、[ADR-0013](0013-capability-tags-removal.md)(「声明形状随真实消费面收敛」)同源;**Supersedes(部分)** [ADR-0008](0008-map-registration.md)——其**决策 4(模板落盘约定不变:`maps/templates/<mapId>/` 按 id 反查模板路径)**随本裁决退场,该决策不再有存续的机制面;同时**修订其决策 2 的形状描述**(「`MapDefinition` 是纯静态值」的判据继续有效——但「纯静态值」的形状自 v1.8 起为 `(worldDirectoryPath, metaAttributes)` 两分量,ADR-0008 落盘时的宽字段 record 为历史形状)
- 验收:★61~★64(§16.2 追加;★54~★55/★57 就地注;★19/★44 冻结快照随 v7 面合法重录——修正案 26 §3/§5 为背书)

## 背景

至 v1.7,地图注册通道(20/22 号收敛后)仍留两个旧形状:

1. **模板寄居约定(ADR-0008 决策 4)**:世界目录母本必须落位在框架数据目录 `maps/templates/<mapId>/`,`MapRegistry(Path templatesRoot)` 持有文件系统根、按 id 反查路径;注册方(宿主/伴随插件)必须知道**框架**的数据目录布局,把自己 jar 里的世界资源解包越区摆位。20 §6-Q1(「是否需要显式模板路径字段」)自 v1.2 登记悬置,文件式通道退场(v1.4)后每个注册方自带落点,约定根再无单一解释。
2. **声明宽字段 record**:`MapDefinition` 有 id/displayName/tags/bounds/spawns/regions/interactPoints/metadata/resetStrategy 九分量;框架对地图的真实消费只有三样——查重键(id)、按标签选图(tags)、副本处置(ResetStrategy);出生点/区域/交互点是模板数据本身(住世界目录内,玩法组件直读世界),历年零消费面;displayName/metadata/bounds 无任何人消费。宽 record 的每个分量都是一份「声明侧合法性自治义务」,膨胀即漂移。

## 决策

**模板寄居约定退场 + MapDefinition 声明瘦身**(全文见 26 号 §1 D1~D4):

1. `MapDefinition` 收缩为 `(worldDirectoryPath, metaAttributes)` 两分量——注册方直交世界目录的**任意落点**(构造期 `toAbsolutePath().normalize()` 规范化入库,缺目录名构造期响亮抛);id **派生** = 声明目录名,查重/副本命名/钉图键三同一源。
2. 框架对路径的唯一校验 = **注册期「存在且为目录」响应响亮抛**(消息带完整绝对路径,20-D3 纪律延续);`MapRegistry` 无模板根——公开构造唯一 = 无参(模板根注入构造口删除,反射守卫);`HydrokarbsAssembly` 撤模板根派生;`WorldPortImpl`/`PlaceholderWorldPort` 以声明路径为基准(复制源 = `map.worldDirectoryPath()`,副本命名与 `deleteCopyDir` 路径形态断言按派生 id)。
3. 声明词料走 `MetaAttributes` 约定键:`map.tags`(缺省空集)、`map.reset-strategy`(**缺省 REUSE_DIRTY 语义不变**——值域与 23 号复用窗口闭环原样);`MapInfo` 视图收缩为 `(id, tags, resetStrategy)`(枚举以 `name()` 进快照)。
4. `AttributeBag` 三口径收口:①声明级专用写口(`MetaAttributes` 门面 / `AttributeBag#declaration` 整表构造)——**组件 `set` 断言不放开**(声明 bag 出生即无组件授权表,泄漏到组件手里也写不进);②注册期冻结与注册方脱钩(`frozen()` + 构造期 `copyOf` 双保险);③声明级生命周期(不参与实例 `close()` 链,随 `MapRegistry` 目录条目存亡)。
5. `API_VERSION` 6 → 7(**方法面零变化**:FROZEN 17 行名单不动;破坏性在声明形状——`MapDefinition` 构造签名与 `MapInfo` 分量,零过渡窗口)。

## 替代方案(已否)

- **保留模板根约定(只把 `maps/templates/<mapId>/` 写进文档四处标注)**:**否,注册方仍需知道框架目录布局**——耦合不对称是世界目录明明是宿主资产却按框架文件系统摆法落位;多注册方(玩法 jar + 测试插件 + 赛程器)各带落点后,约定根需要「谁把东西放进 `<dataDir>/maps/templates/`」的跨方担保,机制开销为负;20 §6-Q1 的「出现真实诉求再加」在文件通道退场后已届至——直交是 Q1 的存量解。
- **`MapDefinition` 保留强类型字段渐进演进(瘦一半留一半,如保 id/displayName/tags/ResetStrategy 四分量)**:**否,与「同一能力一份口径」相悖**——id 保留即与目录名构成**双真源**(目录改名 id 不动,查重键与复制源的对应关系出第二种解释);tags/策略保留单价字段与约定键并存 = 同一词料两份表达(mini 版本仍是双簿记,ADR-0010/0012/0013 同款否决判据)。要么收敛,要么别动;本裁决选全收敛(i 直接派生,tags/策略走约定键,单一口径)。
- **`AttributeBag` 不冻结直接共享引用(注册方门面/bag 直接进目录条目)**:**否,可变引用逃逸**——注册方在 `registerMap` 之后继续 `set(...)` 会**事后改写已注册声明**(标签/策略漂移到已验收目录),A3 选图与副本处置读到的是不可复现的值;20-D1「纯静态值」的判据从此失效。三口径②的构造期冻结(`copyOf`)是「纯静态值」承诺的机械保证,不可省。

## 权衡

- **得**:①**注册方与框架文件系统解耦**——落点自由、解包地缘自洽;框架对路径只余一个布尔校验,`MapRegistry` 的文件系统根机制件(构造口/根持有/路径反查)整体退役。②**声明 = 真实消费面**——record 与快照形状收敛到 id/tags/resetStrategy;查重/副本命名/钉图键单真源,20 §6-Q1 关闭。③**词料口径单行**——tags/策略住约定键,`MetaAttributes` 门面统一持有词汇(写入方与读方同源),声明 bag 生命周期/冻结/写口三口径独立于组件断言面,组件路径零触碰。
- **失**:①**一次性迁移成本**——存量 `MapDefinition` 构造调用与 `MapInfo` 消费方编译期即断(API_VERSION 7,零过渡窗口;当前消费面 = SHDF 宿主与测试/示例插件,随窗口一并迁移);②**注册时刻态校验一次性**——注册后声明目录被删/改无运行期守护(后续 `acquire` future 异常完成,20-D3 响亮路径不走注册闸),宿主自律;登记 26 §6-Q1;③**展示名离开快照**——`MapInfo#displayName` 删除,宿主面板若仰赖快照展示需自持 `mapId → 展示名`(登记 26 §6-Q2,不预置)。
- **判据同源**:与 21-D1(零消费面孔径删除)、22-D3(注册通道单口径)、25-D1(同一能力退平行层)同一推理形状;模板根约定的全部消费方(`MapRegistry` 构造口/`HydrokarbsAssembly` 派生/A6 反查)在退场清单内,机制件无残留消费者。

## 后果

- 正:★61~★64 冻结(声明按路径直交 = 唯一形态 + 模板根与旧结构字段零残留 + bag 三口径);`map/` 包三件(MapDefinition/MapRegistry/MetaAttributes);方法面零变化(API_VERSION 6→7,FROZEN 17 行名单不动、BASELINE 重录);★54~★55/★57 就地注(命题不变)。
- 负:`API_VERSION=7` 窗口内未迁移的消费方编译失败(声明构造/快照分量);20-D2/ADR-0008 决策 4 的约定口径自此只作历史出处;`MapInfo#displayName` 消费方迁移到宿主自持数据。
- 验收:★61~★64(命题文本见 26 §5;落 §16.2 由「编辑·正文与术语」执行);★54~★55/★57 就地注同表。
- 未决移交:26 §6(运行期目录置护不预置 / displayName 回流不预置 / 约定键错类型防护维持读口 cast)。
