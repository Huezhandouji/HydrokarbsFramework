# 阶段3 —— 角色桥接与角色组件(路线图 P2)

> SHDF 玩法线;框架施工线见 ../../../施工计划/
> ★v1.1/v1.2 对齐注:本目录子阶段文档写定于 v1.0,旧包根(`com.shdf.match`)、旧主类(`SHDFMatchPlugin`)与单 jar 口径按 `../README.md` 头部对齐注重读(框架 `com.hydrokarbs`、宿主 `com.shdf.*`、API_VERSION=3)。
> 对应设计案:§8 角色桥接、§9 角色组件层、§11.1 事件流、§12.1/§12.2 代码骨架、§16.1-P2;路线图:§16.1 P2「桥接 + 角色组件」;前置:阶段1(引擎内核)与阶段2(会话层)出口闸门全绿(框架线施工总览 §1:../../../施工计划/README.md)。
> v4 影响:无(`../../08-角色桥接.md` 已补硬边界注)。

## 1. 阶段目标(含出口闸门)

把 `ShadowHunterRolesPlugin`(软依赖)接进框架:先立**防腐层**(L1 `bridge/role`,全工程唯一接触角色插件的地方),再立**游戏侧组件**(L3 `component/role/RoleComponent`,模式唯一消费面),最后立**监听器与验收闭环**(Bukkit 事件入口 + 起服实测)。

**出口闸门(§16.1-P2,不通过不放行,全部要真机跑过)**:

1. 起服后 `/sm debug role info <玩家>` 打印角色 id / 能量 / SanTE(有角色插件时);
2. 实例内击杀 → 实例总线合成出 `RoleKillEvent`(监听器 LOWEST 路由 + §14.4 双源交叉验证);
3. 实例 `stop()` 后全员清角(fake RoleFacade 计数:每人恰好一次)——v3.1 起**引擎无兜底**,`RoleComponent.stop` 是角色清场唯一闸(§9.4);
4. 拔掉角色插件重启:大厅照常可进;声明 `"role"` 能力的模式开局被拒并给出明确提示(§16.2-★21)。

## 2. 子阶段清单表

| 编号 | 标题 | 核心产出 | 出口验收 |
|---|---|---|---|
| 3.1 | RoleFacade 防腐层 | `bridge/role/RoleFacade`(14 方法 1:1 + 阵营五动词 + 懒解析)、`RoleDescriptor`、`RoleFaction` | `RoleFacadeContractTest`:懒解析缺席不炸;roleIcon 缺失回 `AIR`;阵营读写映射与 OTHER 拒绝;hostile 非对称(§8.2/§8.8/§16.3) |
| 3.2 | 类型化访问与策略数据件 | `RoleStateAccess`(OptionalInt)、`RoleCatalog`(TTL 快照)、`RolePolicy` + 三策常量、`RoleSnapshot` | null 三重含义不抛;策略 record 全字段与三常量冻结可共享(§8.3/§8.5/§8.6/§14.14) |
| 3.3 | Capability 软依赖机制 | `engine/session/CapabilityRegistry`(纯机制)、`CapabilityListener` 动态刷新、`strict.require-role` 强校验、`plugin.yml softdepend`、切换阶段 A 能力校验挂点 | `CapabilitySoftDependTest`:"role" 缺席 → lobby 可用、需 role 模式被拒(★21);装/卸动态刷新 + Facade 缓存失效(§8.8/§14.22) |
| 3.4 | RoleComponent 四 facet | `component/role`:RoleComponent + Spec、Loadout/State/Hostility/Rewards 四 facet、`RoleEventSynth`、`RoleKillEvent`/`RoleStateChangedEvent` | §9.7 五测试全绿;stop 全员清角且幂等;awake 零副作用;采样 0=关(§9.3/§9.4/§9.7) |
| 3.5 | 监听器与击杀归属 | `RoleSignalListener`(LOWEST 只路由)、`StatsSignalListener`(MONITOR 记账)、三个占位监听器、`/sm debug role info`、§14.4 双源归属门 | **阶段出口闸门 4 项全过**(起服实测,§16.1-P2;★21/★23) |

依赖顺序:3.1 → 3.2 →(3.3 与 3.4 可并行,都只依赖 3.2)→ 3.5(依赖 3.3 + 3.4)。

## 3. 与其他阶段的接口

**上游已提供(本阶段只消费,不改造)**:

- 阶段1:`MatchModeComponent` 基类与 `Specification`(§2.4)、`MatchModeInstanceServices` 三成员(§2.5)、`MatchModeInstanceEvent` 开放根与 `MatchModeInstanceEventBus`(§12.2)、`EngineTicker`(§12.2)、`AttributeBag`(§2.7)、架构冻结测试套件(`LayeringTest`/`EnginePurityTest` 等,§16.2);
- 阶段2:`SessionService#switchMode` 两阶段切换与 `REJECTED_BY_TARGET_MODE` 拒绝路径(§3.2)、`Session.sessionOf(uuid)` / `Session.currentInstance()`(§12.2 的监听器路由依赖它们)、lobby 模式(大厅只是模式,§4)、`/sm` 命令骨架与 `plugin.yml`(§12.2);
- 外部事实(已核验源码):`RoleAPI` 冻结面 14 方法(`ShadowHunterRoles/src/test/java/com/shadowHunterRolesPlugin/api/RoleApiSurfaceTest.java:39` 的 `FROZEN_SIGNATURES`);阵营组件动词表(`ShadowHunterRoles/src/main/java/com/shadowHunterRolesPlugin/roleComponent/builtin/FactionComponent.java:170`:`faction`/`declared`/`hostile <uuid>`/`enemy_in_range <半径>`/`set <名>`/`reset`);角色系统死亡/掉线清角在默认 **NORMAL** 优先级(`ShadowHunterRoles/src/main/java/com/shadowHunterRolesPlugin/listener/PlayerListener.java:21`、`:39`)。

**下游承诺(本阶段交出去的东西)**:

- 给阶段4(共享组件):`RoleComponent` 强类型读口(`roleIdOf`/`energyOf`/`sanTeOf`/`areHostile`/`occupiedRoleIds`/`applyBuff`/`requestRole`/`applyPending`),其他组件一律 `getComponent(RoleComponent.class)` 消费,不碰桥接(§9.5;★23);`StatsSignalListener` 的记账 seam 由 4.x 的 `StatsComponent` 接管(见 3.5);
- 给阶段5(TDM 模式):`RoleComponent.Spec(BOMB_POLICY/TDM_POLICY/BOSS_POLICY, sampleIntervalTicks, rewards)` 直接可挂;`RoleKillEvent`/`RoleStateChangedEvent` 供规则簿与计分消费(§9.6);
- 给阶段6(bomb/boss):同上,奖励映射(`RoleReward`)与敌对口径已就绪;
- 给阶段7(总验):★21 软依赖降级、★23 角色接入单点两条 ArchUnit/实测在本阶段建立并**永久生效**。

## 4. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 在 NORMAL 之后读死前角色 → 恒为 null(角色系统 NORMAL 清角,§14.5) | `RoleSignalListener` 必须 `LOWEST + ignoreCancelled=true`(§15.1);3.5 验收含"死前角色 id 非 null"断言 |
| 角色插件晚注册/中途卸载 → 构造期取 API 炸、软依赖名存实亡(§14.22) | RoleFacade 懒解析(首次调用 load 并缓存,§8.8);`CapabilityListener` 监听 ServiceRegister/Unregister 动态刷新 + 失效缓存 |
| `executeComponentOperation` 的 null 被当异常(§14.14:null 三重含义) | 桥接层一律 `Optional`/`OptionalInt`,绝不抛(§8.3);字符串动词只住在桥接层,模式不拼 |
| `player.getKiller()` 归因错误(§14.4:箭矢不入账、死亡不清账) | 双源交叉验证门(lastDamagerUuid + 非自杀 + 参与者在场 + 原生归因一致),禁 getKiller;3.5 专测 |
| 模式/命令层 import 桥接 → ★23 变红 | 消费面只有 `RoleComponent` 读口;桥接触点收敛在 `bridge/role` + L0 装配点(装配点依赖的裁决见 3.3 文末「待裁决问题」) |
| 采样做成每 tick → 真成本(§8.4) | 默认 5 tick、`sampleIntervalTicks=0` 关;20 人 ×3 次/5 tick ≈ 12 次/tick,可忽略 |
| stop 漏清角 → 跨局残留(v3.1 无引擎兜底) | 清场唯一闸 = `RoleComponent.stop` 全员 clearRole(幂等);`RoleComponentLifecycleTest` 用 fake facade 断言每人恰好一次 |
| 起服才发现问题 | 每个子阶段 DoD 含 build → runServer 实测闭环(papermc-plugin-dev 技能流程),不起服不算完成 |
