# ADR-0019:出厂空模式 limbo 与回退单通道——会话模式实例不变式,缺省去向管线退场

- 状态:已接受(2026-10-30;经 [31-v1.13修正案](../../31-v1.13修正案-会话模式实例不变式与缺省切换管线退场.md) 落盘为正式修正案)
- 关联:[31-v1.13修正案-会话模式实例不变式与缺省切换管线退场.md](../../31-v1.13修正案-会话模式实例不变式与缺省切换管线退场.md)(本裁决的变更指示与落盘凭据)、[ADR-0009](0009-framework-infrastructure-slimming.md) 的 21-D3「框架不设出厂模式」(本裁决部分修订:框架恰设一个**非玩法**兜底模式)、[ADR-0010](0010-api-surface-consolidation.md)/[ADR-0012](0012-explicit-api-registration.md)/[ADR-0013](0013-capability-tags-removal.md)(「同一能力只留一份口径」与「用户裁决豁免双轨」先例)、[ADR-0018](0018-binary-join-verdict.md)(同 v7 窗口枚举收缩——本次升版锚定,先例分化点见决策 3)
- 验收:本次验收 = `gradlew build` 绿 + `grep -rn "defaultTransition\|DefaultTransition" src/ examples/` 零命中;冻结测试处置见 31 号 §5(★19 FROZEN 17 行零改动、★44 WHITELIST/BASELINE 随分量删除合法重录);验收追加 ★66~★69(计数 65→69)

## 背景

特性要求:**Session 在任何时刻必须有一个模式实例**。v1.12 及之前的现实是三条「无实例驻留」路径:

1. **首切失败**:`create()` 的初始模式为空或未注册 → WARN + 会话保留待后补切换(21-D3/D5 口径)——会话零名册待机可以接受,但名册非空时「有成员而无实例」曾靠二级解散兜底,依赖回退链走通。
2. **回退链空转**:`fallback-mode` 默认空(21-D3「框架不设出厂模式」),空值语义 = WARN 跳过一级直接二级 dissolve;更糟的是 v1.2 前的旧行为里无显式去向 + 空串回退会每拍 A1 拒绝无限空转。
3. **缺省去向管线**:`MatchModeMeta.DefaultTransition`(FALLBACK/STAY/ROTATION)三值枚举是 v2 TransitionPolicy 策略对象的遗骨——引擎只执行 `MatchModeSelection`,STAY/ROTATION 从未被真实消费(评审核验:引擎对它的全部消费 = 无显式去向时映射成一次切换或什么都不做),却占着公共声明面(`MatchModeMeta` 分量)与公共快照面(`MatchModeInfo` 第九分量)。

同时,回退链的防风暴论证有一个隐性漏洞(评审③实证):若回退统一 force 且无驱动层节流,服主把 fallback 配成会无意图结束的真实模式时,「结束→force 切回退→再结束」每约两 tick 一轮,A6 世界获取与 B3.5 清场全在环内——「防风暴由空模式零组件天然闭环」仅对默认配置成立,不能作为机制保证。

## 决策

1. **框架恰设一个非玩法兜底模式**:`EmptyMatchModeFactory`(id = `limbo`)——零组件、`mapTags` 空 = 通配、meta `(false, 0, Integer.MAX_VALUE)`(force 只豁免人数下限、上限永不豁免——上限全开才能兜任意名册)、不读任何配置;经本 jar SPI 清单注册(24 号 ServiceLoader 半边),不在任何提供方 modeClaims,天然防第三方摘除。21-D3 的「框架不设出厂模式」修订为「框架不设**玩法**出厂模式,恰设一个兜底空模式」——引擎源码零 mode id 字面量口径不变(`limbo` 只住 `mode/empty` 与默认资源,禁词表照常扩充)。
2. **id 取 `limbo` 而非特性文直译的 `empty`**:`EngineHasNoModeStringsTest` 是整行子串、大小写敏感的 `line.contains` 扫描,`"empty"` 与 `Optional.empty()`/`Handoff.empty()` 等 Java 惯用词全撞(engine+component 主源 24 文件 59 行);否掉「改匹配语义为引号字面量」(对冻结门检验方式的实质修改、弱化含注释扫描的拦截面),换 id 是零成本解(limbo 全仓 0 命中,语义「悬置等待态」与兜底落点契合)。类名保留 `EmptyMatchModeFactory` 承接特性词汇(类名在 `mode/**` 不被扫描)。
3. **缺省去向管线整体退场,API_VERSION 7→8(经用户裁决不设双轨)**:`MatchModeMeta` 收敛为三分量 `(recordsHistory, minParticipants, maxParticipants)`,嵌套枚举 `DefaultTransition` 与 `MatchModeInfo` 第九分量删除;无显式去向的结束一律回落 `session.fallback-mode`(默认 = limbo)——回退单通道。删公共快照分量 = 跨 jar 破坏,**与 28/29/30 号「维持 7」先例分化**:那三次方法面零变化、破坏在引擎内部形状;本次删的是编译期可见的 record 组件,与 ★v1.7 删方法同构,版本常量必须升以锚定。One-Version 双轨过渡经用户裁决豁免(第 4 次,特性文逐字要求直接删除、无过渡表述)。
4. **回退链单点收敛 `fallbackSwitch(Session, EndResult)` + 驻留节流保留**:回退无效(空串/未注册)或回退切换被拒/异常 → **SEVERE**(原 WARN 跳级)+ 即时二级解散(空串先行即散,不等驻留);回退切换带 force(豁免人数下限,服务不变式)。无显式去向的自动切换三步:空串先行即散 → 驻留不足(`dwellRemaining` 单一公式,与防护①共用)回填重试 → 满后 force 切回退——**节流是机制保证而非配置巧合**;rollback/onQuarantine/create 三路共用同一单点、不加驻留预检(故障自愈立即执行,回退目标再失败即二级解散终止)。
5. **空 mapTags = 通配(模式侧),地图侧不通配**:匹配谓词唯一化(`MatchModeRegistry#matchesTags`),A3 地图解析与 `forMap` 共用——出厂模式在任一有图部署下必然可切;地图侧 tags 为空不构成通配,避免反向放大匹配面。
6. **配置与资源**:`config-version` 3→4(纯迁移标记);`session.fallback-mode` 默认 `"limbo"`;代码级读默认维持空串(单一默认源 = 默认资源);`modes/limbo.yml` 注释-only 文件随默认资源发布(`saveDefaults` 逐资源建父目录)——消除注册期与每次切换到 limbo 的「模式配置缺失」WARN,并给服主可发现的配置落点。

## 替代方案(已否)

- **改禁词门匹配语义(引号字面量匹配)以容纳 id="empty"**:**否**——对冻结门检验方式的实质修改,弱化「扫描含注释」的拦截面(注释里的 `Optional.empty()` 会变成漏网面);换 id 零成本。
- **为空模式另开「无世界实例」通道(不选图、无 WorldHandle)**:**否,结构性分叉**——`PendingSwitch`/B4 结构强绑 map+WorldHandle,另开通道要动切换管线主干;通配选图让空模式走普通切换全路径,失败面已被零组件清空。
- **保留 `defaultTransition` 字段恒值 `"FALLBACK"`(免升版免重编译)**:**否**——冻结面留死词汇,与特性「清除」冲突,且误导消费方以为仍有三种去向。
- **回退统一 force 且无驱动层节流(靠空模式零组件天然闭环)**:**否,评审③否决**——仅对默认配置成立;自定义回退模式依赖无意图结束自持时每约两 tick 一轮风暴,A6/B3.5 全在环内,无任何机制保证。
- **回退不加 force(受人数下限约束)**:**否,惩罚无辜**——自定义回退模式 min>0 时空名册回退被拒 → 解散;force 豁免下限是服务不变式(兜底要兜住任意名册),上限永不豁免。
- **rollback/onQuarantine/create 三路各自加驻留预检**:**否**——故障自愈与首切本就不该等驻留(回退目标再失败即二级解散终止,无死锁);判据单点收敛,不在调用方复制。

## 权衡

- **得**:①「会话有成员而无实例」从二级兜底表述升为**构造性不变式**(默认配置下回退必有落点);②缺省去向三值词汇退出冻结面,声明与快照更短(与 21-D1「零消费者机制件删除」、25-D1「同一能力只留一份口径」同一推理形状);③正常回退运行不再与故障同形(空串/未注册 → SEVERE,唯一的 WARN 不再歧义);④回退环风暴有机制保证的节流;⑤出厂模式零配置面零组件,任何有图部署必然可切。
- **失**:①**API_VERSION 8 破坏窗口**——消费过 `MatchModeInfo#defaultTransition` 的旧编译物需重编译(当前零真实消费者,风险为假想面,31 §6-Q1);②**首切失败泊回退的行为回退**——后补非 force 切换受最短驻留约束(宿主可 force 或等 100 tick,31 §6-Q3);③`limbo` 成为框架保留 id(宿主同名注册被响亮拒,31 §6-Q5);④回退目标为玩法模式时每窗口至多一轮「结束→切回退」抖动仍可观测(31 §6-Q4);⑤摘除编排既有 gap(被拒仅 WARN,旧实例继续跑)维持观察项(31 §6-Q2)。
- **版本轴口径分化**:28/29/30 三次「方法面零变化维持 7」先例不适用本次——删公共快照分量与删公共方法同构,升 8 锚定;后续裁决以「破坏是否编译期可见地落在公共面」为升版判据。

## 后果

- 正:`EmptyMatchModeFactory`(id=limbo)经 SPI 注册;`MatchModeMeta` 三分量、`MatchModeInfo` 八分量、`API_VERSION = 8`;`fallbackSwitch` 单判据(无效/被拒/异常 → SEVERE + `FALLBACK_DISSOLVE`);`driveAutoSwitch` 三步 + `dwellRemaining` 单一公式;`config-version = 4`、`fallback-mode` 默认 `"limbo"`、`modes/limbo.yml` 随默认资源发布;验收追加 ★66~★69(计数 69 条);★19 FROZEN 17 行不动、★44 合法重录。
- 负:旧编译物需按 v8 重编译;`NeutralDefaultsSemanticsTest`/`SwitchStormGuardTest` 语义重写(修测理由 = 31 §1-D6);`limbo` 入禁词表后引擎/组件源码禁用该词(与 `lobby` 同款约束)。
- 文档修改点(本裁决同步落盘):`03-会话与模式切换.md`(§3.2 回退表/触发入口③/§3.3/§3.4/§3.5、头部版本行);`02-核心概念.md`(§2.2/§2.3 MatchModeMeta);`07-公共API.md`(§7.1 API_VERSION/版本链/§7.2 值对象/版本协商行);`12-代码骨架.md`(§12.1 mode/empty、SPI 行、§12.2 骨架注、增量注);`13-扩展步骤.md`(§13.3/§13.4/§13.7/§13.11);`14-陷阱清单.md`(14.17/14.21 注);`15-PaperMC适配.md`(§15.2 config 块/目录树);`16-路线图与验收.md`(★66~★69);`00-假设与体检.md`(§0.2-A3 注、§0.5.18);`CONTEXT.md`(词条与历史术语表);`README.md`/`AGENTS.md`(版本链与 §4-10 API 面例外句)。
