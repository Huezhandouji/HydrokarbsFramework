# ADR 0003:JoinPolicy 从 MatchModeMeta 下放为组件(JoinControlComponent)

- 状态:已采纳(2026-10-17)
- 关联:0001(大厅即模式)、0002(聚合根零行为与引擎纯度);设计案 v3.2(§0.5.4、§3.2.1、§6.1-#24)

## 背景

v3.0 把 v2 `Mode` 接口上的 `joinPolicy()` 方法收敛为 `MatchModeMeta.joinPolicy` 字段(枚举 OPEN/SPECTATE/CLOSED),当时的理由是"它们本来就是数据,不是行为;数据归数据"。`SessionService#join` 按当前模式的该枚举路由:OPEN 接纳 / SPECTATE 转观战 / CLOSED 拒绝。

## 决策

v3.2 删除 `MatchModeMeta.joinPolicy` 字段与 `JoinPolicy` 枚举,入场裁决改为组件实现:

1. **引擎新增端口** `engine/port/JoinAdmission`:`JoinDecision admit(player, ctx)`,裁决只有三种结构化结果 `ADMIT / SPECTATE / REJECT`(§3.2.1)。引擎只认识裁决结果,不认识任何具体入场策略——与 `EndResult` 只带去向同一口径。
2. **L3 新增标准实现** `JoinControlComponent`(§6.1-#24):策略是 Spec 构造数据(如 `MidJoin.REJECT_WHEN_RUNNING`),可按阶段门控、可携带更多规则;模式用 Builder 声明挂载。
3. **两个触发点**统一消费同一端口:切换 B4 的名册初始摆放、运行中 `join`(REJECT 时会话不收;ADMIT/SPECTATE 都登记名册,差别在成为 Participant 的方式)。
4. **不挂任何实现 = 默认全接纳**:大厅与极简模式零配置——"不介入"的表达是不声明,与大厅不挂 RoleComponent 同一口径。

## 理由

- **入场路由是行为,不是数据**:它可能依赖阶段(仅 Warmup 可进)、人数(满员转观战)、队伍状态——静态三值枚举把策略写死成三个名字,新规则就要动引擎公共契约 meta。这正是 §14.12 警惕的"把行为编码进数据枚举",也与 v3 核心决断"行为溢出即设计失败"(§2.8-决断二)同源。
- **一致性**:Phase/EndGate/RuleBook 三迁(v3)与本次"第四迁"是同一逻辑——判定一个模式"要不要入场控制"与"要不要队伍/重生"是同一件事,都是 Builder 里加不加一行 `addComponent`。
- **契约更稳**:meta 是冻结的公共声明面;枚举值只会越加越多(每个新玩法都可能带来新入场规则),组件 Spec 是可扩展的构造数据,加规则不动公共契约。

## 后果

- `MatchModeMeta` 减一字段,所有模式工厂的 meta 构造相应减参(§2.3、§4.2、§12.2 同步)。
- `engine/port` 增 `JoinAdmission`/`JoinDecision`,`engine/session` 增 `JoinResult`(Joined/Rejected);`SessionService#join` 注释与实现改为端口查询。
- §6.1 组件清单 23→24 行(L3 实数 21,因 #20/#21/#22 住 `mode/**`);P3 验收计数与施工计划阶段4 闸门同步。
- 不挂该组件的模式行为与旧 OPEN 完全一致,存量声明(大厅/极简大厅)无需改动。
