# 阶段8 — 独立插件化交付(★v1.1 新增;路线图 P8.5;G2 闸门)

> HydroKarbs MiniGame Framework · v1.3(现行;v1.2 于 2026-10-23 地图注册通道;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充)。
> 对应设计案:`19-v1.1修正案`(§8 施工子阶段与交付顺序约束)、`12-代码骨架.md`(§12.1 v1.1 交付形态注与形态 B 双工程树)、`15-PaperMC适配.md`(§15.2 形态 B 配置分工注)、`16-路线图与验收.md`(§16.1 P8.5、§16.2 ★47~★50)。决策记录:ADR-0007。术语以根目录 `CONTEXT.md`(「交付形态」节)为准。
> **触发条件(不满足不开工)**:7.5(G1)全绿 **且** 首个真实跨 jar 消费者(伴随插件或第二宿主)立项——A13,Assumption-driven,不做 hypothetical seam。长期无跨 jar 诉求则本阶段不启动,形态 A 即终态。

## 1. 阶段目标

把"单 jar 彩排"过的边界换成**物理拆分**(形态 B,超集交付、不是分叉——同一套源码与同一验收集):

1. **Gradle 双工程/复合构建**:框架插件 `HydroKarbs`(`com.hydrokarbs.*`,主类 `com.hydrokarbs.HydroKarbsPlugin`)+ 玩法插件 `ShdfHost`(`com.shdf.*`,主类 `com.shdf.ShdfPlugin`;`depend: [HydroKarbs]`,compileOnly + 禁 shade)。
2. **玩法插件 depend 接线与资源迁移**:玩法 `modes/*.yml`、玩法 `maps/*.yml` 与玩法图模板随玩法 jar(各自 `maps/` 根,"模板随地图包分发");框架 `config.yml` 管拓扑与会话节律;**会话拓扑装配延迟至全部插件 enable 完成后**(首 tick / `ServerLoadEvent`),使 `initialModeId` 可解析玩法注册的模式。(**★v1.3-D5:后半句废止**——`session.topology`/`arenas`/`initial-mode` 键删除,框架 onEnable 零会话;建会话(填 `initialModeId` 等)是宿主在全部插件 enable 完成后调 `SessionService.create(SessionSpec)` 的编排动作,时序由宿主自理,21-D5)
3. **卸载路径(D8)**:框架监听 `PluginDisableEvent`——①摘除该插件注册的全部模式;②正跑已摘模式的会话 → `switchMode(fallback)`(三级回退既有机制),无可用回退目标 → dissolve;③摘除其注册的能力标签;④摘除其 provider 贡献(已装配会话不受影响);⑤残留句柄断言(★47)。PlugMan/运行期重载走同一摘除路径。
4. **预算观测(D9③)**:`EngineTicker` 对组件 `update` 计时,单组件单 tick 超阈值(默认 5ms,`performance.component-budget-ms`)记 WARNING(会话 id/组件 id/提供方插件;覆盖实例组件与会话组件,含跨 jar 第三方贡献);**硬熔断不做**(未决 19-§7-Q4,倾向只告警)。

## 2. 子阶段清单

| 编号 | 标题 | 核心产出 | 出口验收 |
|---|---|---|---|
| 8.1 | 物理拆分与双形态交付 | 双工程构建 + depend 接线与资源迁移 + `PluginDisableEvent` 摘除路径 + 预算观测 + 双形态等价复验 | 形态 B 下 ★47~★50 全绿;★50 断言同一验收集(★1~★55,随验收追加同步扩)在形态 A/B 下结论一致 |

## 3. 与其他阶段的接口

**上游已提供(本阶段只消费、不重建)**:

| 来自 | 已提供什么 | 本阶段消费点 |
|---|---|---|
| 7.5(G1) | API v3 面与白名单冻结(`ApiSurfaceTest`)、包根 `com.hydrokarbs`、D2/D3/D4/D14 实现、地图注册通道(★v1.2)、测试伴随插件、★41~★46/★51~★55(形态 A) | 拆分后直接复跑;伴随插件即"首个真实跨 jar 消费者"的验证载体 |
| 7.2 | `ServiceRegisterEvent`/`ServiceUnregisterEvent` 收录/摘除通路 | D8 摘除路径的事件基础 |
| 2.3 | 三级回退(fallback → dissolve) | 玩法插件 disable 时受影响会话的回退执行 |

**下游承诺(本阶段交付后对外生效)**:

| 交付物 | 承诺 |
|---|---|
| 双工程产物 | 框架与玩法可独立发版;消费方纪律(compileOnly + depend + API_VERSION + 禁 shade)成为发布文档的一部分 |
| 卸载路径 | 任何消费插件 disable 不毁服:模式摘除 → 会话回退/解散 → 标签/贡献摘除 → 句柄断言(★47) |
| 预算观测 | 第三方组件拖慢 tick 可观测(日志点名),为 Q4(硬熔断)积累数据 |

## 4. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 拆分后白名单遗漏,宿主编译断在内部面 | G1 彩排纪律已排练过;编译失败清单逐条裁决——扩白名单(走 ★44 快照)或改走 L0 豁免点,不留暗道 |
| 双形态行为漂移(同验收集结论不一致,★50 红) | 以形态 A 为基准逐条比对;漂移即回 8.1 修接线,不允许"按形态分叉断言" |
| 加载顺序问题(玩法晚 enable、initialModeId 不可解析) | D8 时序约束:拓扑装配延迟至 `ServerLoadEvent`/首 tick;测试覆盖"玩法插件晚于框架 enable"场景(**★v1.3-D5:该缓解随启动建会话废止——框架不建会话,宿主在全部插件 enable 完成后自行调 `create`,时序问题不再存在**) |
| PlugMan 热重载句柄残留(14.38) | 摘除路径全覆盖 + 残留句柄断言(★47);句柄只在插件实例字段的纪律写入消费方文档 |
| 过早触发 G2(API 面未实战检验) | A13 触发条件硬门:无真实跨 jar 消费者不启动本阶段 |
