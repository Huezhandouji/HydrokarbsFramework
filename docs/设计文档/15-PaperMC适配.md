## 15. PaperMC 1.21.11 适配要点(必填章节)

> HydroKarbs MiniGame Framework · v1.3(现行;v1.2 于 2026-10-23 地图注册通道;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充;v1.0 于 2026-10-22 自 SHDFMatch 设计案 v4.1 剥离玩法重构;玩法实现见 Shdf具体玩法设计案/)
>
> 文档版本:v1.3(2026-10-25,框架基础设施瘦身与配置中立化——§15.2 config 顶层结构 v1.3 版(database/display/shutdown-flush 删除、initial/fallback 默认空、arenas 注释化、config-version 3)、文件分工树 secrets/lang 注归宿主、§15.3 流水线②与范例注迁宿主,21-D1~D3;**同日 D5 增补:§15.2 session: 块收缩为三键(topology/arenas/initial-mode/dissolve-after-empty-seconds 随启动建会话特性删除,21-D5)、L0 兜底注同步**;v1.2 于 2026-10-23 地图注册通道——§15.2 reload 分级注与模板落盘口径;v1.1 于 2026-10-23 框架独立插件化拆分与 API 面扩充——§15.2 形态 B 配置分工注与 `performance.component-budget-ms` 新键;v4.1 于 2026-10-21 进程级服务层与 ServiceRegistry;会话级组件层——两级挂载)。

### 15.1 事件监听:Paper Event API 使用规范

#### 优先级选择表(本项目的实际用法,不是泛泛而谈)

| 我们的监听器 | 监听事件 | 优先级 | `ignoreCancelled` | 理由 |
|---|---|---|---|---|
| `RoleSignalListener#onDeath`(**SHDF 扩展线**,见 Shdf具体玩法设计案/08-角色桥接.md) | `PlayerDeathEvent` | **LOWEST** | `true` | ★ 必须早于宿主角色系统的 NORMAL 清角,才能读"死前角色";只路由到 RoleComponent(§12.6) |
| `StatsSignalListener#onDamage` | `EntityDamageByEntityEvent` | **MONITOR** | `true` | ★v3.1 从 RoleSignalListener 拆出(伤害记账与角色无关);只记账不改状态,放最后拿最终伤害值 |
| `SessionIngestListener` | `PlayerJoinEvent` / `PlayerQuitEvent` / `PlayerRespawnEvent` / `PlayerKickEvent` | `NORMAL` | `false` | 会话名册登记/退出,常规流程编排 |
| `InteractionListener` | `PlayerInteractEvent` | `NORMAL` | `true` | 只处理真正生效的交互(被取消的不该起读条) |
| `InteractionListener#onMove` | `PlayerMoveEvent` | `MONITOR` | `false` | 打断读条用的**只读观察**;高频事件,内部必须"先做廉价判断再做事" |
| `ProtectionListener` | `InventoryClickEvent` + **`InventoryDragEvent`** | `HIGH` | `true` | ★ 只处理 `Click` 会漏掉拖拽;`HIGH` 早于 GUI 逻辑拦截 |
| `CapabilityListener` | `ServiceRegisterEvent` / `ServiceUnregisterEvent` | `NORMAL` | `false` | ★v2:宿主插件装/卸时刷新 CapabilityRegistry(SHDF 扩展线实例:监听角色插件,见 Shdf具体玩法设计案/08-角色桥接.md) |
| 调试/审计 | 任意 | `MONITOR` | `true` | 纯观察 |

#### 规范清单

| 规范 | 说明 |
|---|---|
| **`ignoreCancelled` 的语义** | 只对**可取消**的事件有意义。`PlayerDeathEvent` **不可取消**(不实现 `Cancellable`),写了是 no-op 但不报错——容易误以为"已经过滤了"。`EntityDamageEvent` / `PlayerInteractEvent` / `InventoryClickEvent` 都可取消。 |
| **"拦下原版死亡"要在伤害事件做** | `PlayerDeathEvent` 不可取消 ⇒ 想阻止死亡必须在 `EntityDamageEvent` 上 `setCancelled(true)` 或回血。 |
| **没有 `AsyncEvent` 接口** | 异步性是 `Event#isAsynchronous()` 的**属性**,不是类型。 |
| **自定义事件** | `MatchModeInstanceEvent`/`SessionEvent` 是**内部总线**,不是 Bukkit Event。若需向第三方暴露(如 `HydroKarbsMatchEndedEvent`),才继承 `Event` 且 `isAsynchronous()` 返回 `false`。 |
| **监听器注册时机** | 全部在 `onEnable` 注册。需要阶段级/模式级开关就用内部总线订阅,**不要**在切换时反复 `registerEvents`。 |
| **监听器不做重活** | 事件回调在服务端 tick 线程同步执行。回调只做:**路由到会话 → 发内部事件 → 返回**。 |
| **投射物归因** | `EntityDamageByEntityEvent#getDamager()` 可能是 `Arrow`/`Trident`。要拿"谁射的"看投射物 `Shooter`。本设计不依赖事件源做归属,只用它做交叉验证(§14.4,SHDF 扩展线条目,见 Shdf具体玩法设计案/08)。 |

### 15.2 配置管理

#### 文件分工(`plugins/HydroKarbs/`)

> ★v1.1 配置分工注(19-D1):**形态 B(双 jar)下玩法配置随玩法 jar**——`modes/*.yml`(玩法模式参数)与玩法 `maps/*.yml`、玩法 `maps/templates/` 归玩法插件资源(各自 `maps/` 根,"模板随地图包分发");框架 `config.yml` 管会话节律(下节;★v1.3-D5:拓扑与会话创建移宿主,框架不再持有 topology/arenas 键),通用图模板随框架 jar。形态 A(单 jar)下两者同居 `plugins/HydroKarbs/`,分工口径不变。

```
plugins/HydroKarbs/
├── config.yml              # 引擎/会话/基础设施,全局单例
├── modes/
│   ├── lobby.yml           # ★v2:大厅也是 modes/ 下一个文件(倒计时/投票池/闲玩开关)(SHDF 玩法线,见 Shdf具体玩法设计案/04、05)
│   ├── bomb_defusal.yml    # (SHDF 玩法线,见 Shdf具体玩法设计案/05)
│   ├── team_deathmatch.yml # (SHDF 玩法线)
│   └── boss_raid.yml       # (SHDF 玩法线)
├── maps/
│   ├── lobby_main.yml      # ★v2:大厅地图(与玩法地图同格式)(SHDF 玩法线,见 Shdf具体玩法设计案/04、05)
│   ├── crossfire.yml       # 每张地图一个文件(SHDF 玩法线,见 Shdf具体玩法设计案/04、05)
│   └── templates/          # ★v2:世界模板母本(mapId 目录)
├── (★v1.3:secrets.properties 已迁宿主——框架基线无 DB 密码;宿主自建持久化时密码文件
│          放宿主插件自己的数据目录,21-D1)
└── (★v1.3:lang/ 文案目录归宿主插件(框架消息文案最小化,随宿主语言包),21-D2/D3 口径)
```

#### `config.yml` 顶层结构(★v1.3 版:`config-version` 2→3——`database:`/`display:` 块与 `engine.shutdown-flush-budget-seconds` 删除、`session:` 块经 21-D3 中立化后再经 **21-D5 收缩为三键**(topology/arenas/initial-mode/dissolve-after-empty-seconds 随启动建会话特性删除)、`fallback-mode` 默认改空;21-D1~D3/D5)

```yaml
config-version: 3            # ★ 每个配置文件都要有,用于迁移。★v1.3:2→3(旧 database:/display: 块
                              #   迁移时被忽略并提示归属变更;见 21 §2 配置 diff)

engine:
  tick-period: 1             # EngineTicker 周期(tick);1 = 每 tick
  # ★v1.3:shutdown-flush-budget-seconds 已删——它服务于统计 write-behind 的停服冲刷,随 L5
  #   持久化整体迁宿主线(21-D1/D4-②);框架 onDisable 回归 EngineTicker.close() 既有三步,无 DB 冲刷语义
  # ★v3.1:role-sample-interval 已删——采样间隔是角色扩展的玩法内容参数,不住引擎配置块
  #   (SHDF 扩展线:现住 modes/*.yml 的 role: 块,见 Shdf具体玩法设计案/05-四模式矩阵.md §5.4、09-角色组件层.md);
  #   display-refresh-interval 已随 display: 块于 v1.3 一并删除(表现层出框架,21-D2)。
  #   引擎配置块不出现内容参数(§0.5.3)。

session:                     # ★v1.3-D5:恰剩三键——会话由宿主经 SessionService.create(SessionSpec) 创建,
                             #   框架 onEnable 零会话(零会话起服为合法状态)。★v1.3-D5 删:
                             #   topology / arenas / initial-mode / dissolve-after-empty-seconds——
                             #   拓扑 = 宿主调几次 create;开局模式 = 宿主填 SessionSpec.initialModeId;
                             #   空场组件时长 = 宿主组装 SessionSpec.sessionComponents 时直接喂
                             #   EmptyArenaComponent.Spec.after(键与 L0 装配分支一并废止,21-D5)
  fallback-mode: ""          # ★ 切换失败的回退模式——配置项,引擎不认识任何具体 id。★v1.3:默认空;
                             #   回退去向 = 配置指向的任意已注册模式,为空或未注册 → 回退链跳过一级、
                             #   直接二级 dissolve(21-D3-②;内核节律,与谁建会话无关,D5)
  min-dwell-ticks: 100       # 最短驻留(防切换风暴;内核节律,21-D5 保留)
  disconnect-grace-seconds: 60

# ★v1.3:database: 块(九键)整体删除——持久化出框架,宿主自建 DB 栈(21-D1);连接信息与
#   密码方案(secrets)随宿主插件自理
# ★v1.3:display: 块(refresh-interval/三开关/标题三段时长)整体删除——表现层出框架(21-D2);
#   刷新节拍与开关随宿主插件配置,设计资产见 Shdf具体玩法设计案/10-表现层组件.md

# strict.require-role(角色硬依赖开关)随 SHDF 扩展线、不属框架基线——见 Shdf具体玩法设计案/08-角色桥接.md §8.8

performance:
  component-budget-ms: 5     # ★v1.1-D9③:单组件单 tick 预算(超阈值记 WARNING——会话 id/组件 id/提供方
                             #   插件;覆盖实例组件与会话组件,含跨 jar 第三方贡献;硬熔断不做,未决 19-§7-Q4)
                             #   ★v1.3 保留(21-D4-①):信任边界观测属框架机制、非玩法——display 块删除不影响本键
```

> **★v1.3 L0 装配读键口径**(21-D3-②/D5):切换回退去向 `cfg.get("session.fallback-mode", "")`——框架源码与缺省配置均不再出现 `"lobby"` 字样;**原 `cfg.get("session.initial-mode", …)` 行已随 21-D5 整体废止**(拓扑装配删除,初始模式由宿主填 `SessionSpec.initialModeId`;D3-① 的启动装配空值语义不再有框架侧触发点)。

> v1 的 `lobby: { world: ..., spawn: ... }` 块**已删除**——大厅的世界与出生点就是 `maps/lobby_main.yml` 的 `world` 与 `spawns.lobby`,与其他地图同源。v1 的 `match:` 块并入 `session:`。
>
> ★v4.1:进程级服务(§3.9)不新增任何配置键——第一版服务装配硬编码于 L0(`HydroKarbsAssembly`,§12.1)。
>
> ★v4.1 未决(修正案 §12-Q10):**服务装配是否配置化**(services.yml 声明式)——第一版 **L0 硬编码**;该未决项登记于此、待裁决,不在本版静默决定。

> 地图与模式参数的完整示例(`maps/crossfire.yml`、`modes/bomb_defusal.yml`,含 `role:` 策略块)属具体玩法内容,已迁 Shdf具体玩法设计案/05-四模式矩阵.md §5.4(配置示例);框架侧只保留其上的通用纪律——地图文件纯静态、无任何规则字段;模式文件只含可调平衡参数、不含坐标。

#### 解析实现要点

```java
package com.hydrokarbs.bridge.config;

/** YAML → 不可变 record 的唯一编解码点。★ 解析必须在虚拟线程上做。 */
public final class YamlCodec {

    private final Yaml yaml = new Yaml();      // SnakeYAML(Paper 自带)

    /** 每张地图一个文件:读 → 校验 → 转 record。错误信息必须带文件名与行号。 */
    public MapDefinition readMap(Path file) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var root = yaml.<Map<String, Object>>load(reader);
            return MapDefinition.from(root, file.getFileName().toString());   // 内含校验
        } catch (IOException e) {
            throw new ConfigException("读地图文件失败: " + file, e);
        } catch (RuntimeException e) {
            throw new ConfigException("地图文件格式错误: " + file, e);        // ★ 带上文件名
        }
    }
}
```

**配置容错三铁律**(v1 原样)
1. **缺键必须有默认值**:`cfg.getInt("rounds.best-of", 13)`,绝不裸取。
2. **非法值降级 + 警告,不崩**:`rounds.best-of: -5` → 用默认 13 并 `WARN`。
3. **加载期校验(fail-fast 但"局部失败")**:单张地图失败只跳过该图并 SEVERE;**切换前**若该图不满足所选模式(`validateMap` 非空),拒绝切换并打印缺失项——不静默降级。

**热重载**:`/sm reload` 分级:`config`(引擎/会话;新会话生效)/ `modes`(含 lobby.yml;进行中实例不受影响)/ `maps`(有会话正用该图则拒绝)。虚拟线程解析 → 主线程原子替换 `MapRegistry` 引用。(注:modes/maps 两级涉及的 lobby.yml、lobby_main.yml、crossfire.yml 属 SHDF 玩法线,见 Shdf具体玩法设计案/04、05)
> ★v1.2 注(20-D5):`maps` 级 reload **只触碰文件式地图**;注册式地图(经 `MiniGameApi#registerMap`)不经 reload——声明所有权在插件,宿主"reload" = `unregisterMap` + `registerMap`(或整插件重载,走 PluginDisableEvent 摘除)。模板落盘口径(20-D2):注册式地图的模板解析路径仍为框架数据目录 `maps/templates/<mapId>/`,宿主注册前自行解包(`saveResource`)落盘,注册期校验目录存在(14.39)。
> ★v4 未决(变更指示 §11-Q4):**会话组件热替换/热重载语义**——第一版**不支持**:本节三级 reload 不覆盖会话组件表(装配来源是 L0 在 `SessionService.create` 内按配置构建的 `SessionSpec.sessionComponents`,§3.8/§6.4);该未决项登记于此、待裁决,不在本版静默决定。
>
> ★v4.1 未决(修正案 §12-Q11):**服务热替换/热重载语义**——第一版**不支持**:进程生命周期 = 插件生命周期,本节三级 reload 不触及服务层。该未决项登记于此、待裁决,不在本版静默决定。

### 15.3 调度器:Scheduler 与 Java 虚拟线程的配合方式

#### 三条流水线的分工

```
┌─────────────────────────────────────────────────────────────────────────┐
│ ① 游戏节拍线(主线程 / GlobalRegionScheduler)                            │
│    一个周期 1 tick 的任务:EngineTicker#tickAll                            │
│      ├─ Session#runPendingSwitch(模式切换的唯一驱动点)                    │
│      ├─ MatchModeInstance#tick():组件 update(阶段计时/结束求值在组件内,引擎不解释)│
│      ├─ 通道结算推进(交互读条、阶段转场)                             │
│      ├─ 会话组件派发:tickSessionComponents()(★v4.0:tickAll ③ 步,主线程) │
│      └─ 内核 sweep:断线/重生宽限(★v4.0:空场倒计时已下放会话组件)        │
│    ★ 只做 O(成员数 × 组件数) 的纯内存计算,单次预算是「微秒级」           │
└─────────────────────────────────────────────────────────────────────────┘
                                    ▲
                                    │ 只能通过 Threads#runOnMain / runTask 回程
┌─────────────────────────────────────────────────────────────────────────┐
│ ② 文件 IO 线(虚拟线程)——★v1.3:数据库各线随持久化迁宿主线(宿主自建     │
│    AsyncExecutor/HikariCP 池时沿用同一纪律),框架侧仅余:                  │
│    AsyncExecutor(Executors.newVirtualThreadPerTaskExecutor())            │
│      ├─ 配置解析                                                          │
│      └─ ★v2:世界模板复制(切换阶段 A6);完成后回主线程加载世界            │
│    (历史行,迁宿主线:档案加载/统计读取/排行榜/对局历史写入/StatsFlusher    │
│     批量 UPSERT/迁移 DDL——21-D1)                                          │
│    ★ 绝不碰任何 Bukkit 对象                                              │
└─────────────────────────────────────────────────────────────────────────┘
                                    ▲
                                    │ 只被①发起(fire-and-forget + 回调)
┌─────────────────────────────────────────────────────────────────────────┐
│ ③ 延迟调度线(主线程 / 一次性 runTaskLater)                              │
│    结算停留、转场动画、消息延迟;句柄统一挂在实例上,close 时集中 cancel   │
└─────────────────────────────────────────────────────────────────────────┘
```

> ★v4.1:服务不占 tick 步骤,经自身订阅与 `later` 驱动,全程主线程。

#### 具体 API 选择

| 需求 | 用什么 | 代码 |
|---|---|---|
| 每 tick 驱动会话/实例 | `Bukkit.getScheduler().runTaskTimer(plugin, task, **1L**, 1L)` | 初始 delay 必须 > 0 |
| 延迟 N tick 执行(主线程) | `runTaskLater(plugin, task, N)` | 句柄登记到实例 |
| 提交 DB/文件任务 | `Executors.newVirtualThreadPerTaskExecutor()` | **不用** `runTaskAsynchronously` |
| 从异步回主线程 | `Bukkit.getScheduler().runTask(plugin, r)` | 或 `globalRegionScheduler().execute(plugin, r)` |
| 已经在主线程就别调度 | `Threads.runOnMain(r)`(判 `Bukkit.isPrimaryThread()`) | 避免白等一 tick |
| 关闭时的有界等待 | `future.get(3, SECONDS)` 只在 `onDisable` 里允许 | 唯一合法的阻塞点 |

#### 为什么 DB IO 用虚拟线程而不是 `runTaskAsynchronously`(v1 结论原样)

| 维度 | `runTaskAsynchronously`(Paper 固定异步池) | 虚拟线程 |
|---|---|---|
| 并发上限 | Paper 默认 4 个异步线程,**会被 DB 等待占满**,阻塞其他异步任务 | 每任务一条虚拟线程,"等 JDBC"不占平台线程 |
| 阻塞友好度 | 差:池被 IO 堵住后其他任务排队 | 好:为阻塞 IO 设计 |
| 适合的活 | 短小 CPU 计算 | **阻塞 IO**(JDBC、文件、世界模板复制) |

**结论**:`runTaskAsynchronously` 完全弃用;DB 与文件 IO(含模板复制)统一走 `AsyncExecutor`;Paper 的异步池留给服务端自身。

#### 一个完整的"异步读 → 主线程用"范例(v1 原样,类型更名;★v1.3:档案拉取链路迁宿主线——本例对宿主自建持久化同样成立,框架侧的回程纪律(`Threads.supplyThenOnMain`)不变,21-D1)

```java
// 玩家加入会话时:异步拉档案 → 主线程应用(语言、偏好)——★v1.3 起此链路由宿主实现(宿主的 persistence)
public void onMemberJoin(Session session, Player player) {

    String dbMode = session.currentInstance().map(i -> i.mode().id()).orElse("TOTAL");
    Threads.supplyThenOnMain(
        // ── 虚拟线程:纯 IO ──(★v1.3:persistence 为宿主自建持久化面,非框架 PersistencePort——该接口已删)
        () -> persistence.statsBlocking(player.getUniqueId(), dbMode),
        // ── 主线程:应用 ──
        stats -> {
            player.sendMessage(Text.info("欢迎回来!累计击杀 %d"
                    .formatted(stats.map(PlayerStats::kills).orElse(0L))));
            // (SHDF 扩展线:此处原有"恢复上次角色"回填示例——lastRoleId → RoleComponent.requestRole,见 Shdf具体玩法设计案/08-角色桥接.md)
        },
        // ── 主线程:错误处理(降级,不中断)──
        err -> log.log(Level.WARNING, "读取玩家档案失败,按新玩家处理: " + player.getName(), err)
    );
}
```

---
