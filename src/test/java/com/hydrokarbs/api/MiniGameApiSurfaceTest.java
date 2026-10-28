package com.hydrokarbs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * 公共 API 冻结(★19;7.1 施工文档步骤 8;7.5 步骤 11 快照 8→16;★v1.4 收敛
 * 16→15;★v1.6 显式注册收敛 15→19;★v1.7 能力标签退场 19→17):反射冻结 v6
 * 方法集(17 方法 = v1 面 7【查询 3 + 控制 1 + 目录 3】+ 7.5 增量 6【D2 成员 2 +
 * D3 订阅 1 + D14 recipe 建会话 1 + ★v1.2 地图注册 2】+ ★v1.6 增量 4【模式显式
 * 注册 2 + 会话组件显式注册 2】),归一为 {@code "name(参数类型列表):返回类型"}
 * 规范串集合与清单常量<b>全等</b>。后续任何删/改签名即测试红——「只增不改」
 * (07-公共API §7.2)。
 *
 * <p><b>v1 面签名零改动</b>(7.5 DoD「diff 核对」条目):v1 面 8 行在 7.5 收敛前
 * 与 7.1 冻结时逐字节一致;★v1.4 经用户裁决删除其中的两参
 * {@code createSession(String, String)} 1 行,余 7 行签名一字不动(只增不改的
 * 破坏性例外,见 {@link MiniGameApi} 头部 ★v1.4 收敛注);其余 10 行 = 7.5/★v1.6
 * 增量(07 §7.1 v3 面/19 §3 契约骨架/20 §2 契约骨架/24 号修正案),新增依据逐行注释出处。
 * ★v1.7(25 号修正案)经用户裁决删除 ★v1.1-D4 的能力标签两法
 * (破坏性例外:能力标签机制全链退场,外部能力 = 会话
 * 组件桥接),其余 17 行签名不动。
 *
 * <p><b>「只增不改」运营细则(给未来维护者;7.1 关键实现要点 7)</b>:
 * <ol>
 *   <li>新增方法 = 追加本清单 + PR 单独说明动机与消费者(谁在调它);</li>
 *   <li>改签名 = 视同删 + 增,需新消费场景论证;已有调用方同步改;</li>
 *   <li>删除方法原则上不允许——先标 {@code @Deprecated}(javadoc 指明替代),
 *       一个发布周期后再议;</li>
 *   <li>返回的 record 加字段 = 对消费者无损(构造方兼容),仍需在清单外补
 *       record 分量断言(可选,不强求)。</li>
 * </ol>
 *
 * <p>归一化规则:参数类型按声明序、返回类型取 {@code Class#getName()};泛型实参
 * 经反射不可区分——{@code CompletableFuture<…>}/{@code Consumer<…>} 等元素类型由
 * {@code MiniGameApiImplTest} 补断言(方法名在清单里,改名即红,不漏报)。
 * 战绩两方法(stats/top)随 ★v1.3/R1 悬置(21 §6-Q1),不在清单(签名类型
 * {@code PlayerStats}/{@code LeaderboardRow} 已随 L5 data/model 出框架);
 * 回填 = 细则第 1 条流程。API_VERSION = 6(v1.0=1;v1.1=2;v1.2=3;★v1.3 维持;
 * ★v1.4 = 4;★v1.6 = 5;★v1.7 = 6,能力标签机制退场)。
 */
class MiniGameApiSurfaceTest {

    /** v6 冻结清单(17 行对 17 法;v1 面 7 行逐字节冻结,后 10 行 = 7.5/★v1.6 增量)。 */
    private static final Set<String> FROZEN = Set.of(
            // ── v1 面(7.1,2026-10-25;8 行 → ★v1.4 删两参 createSession 余 7 行,
            //    签名一字不动) ──────────────────────────────────────────────
            "sessionOf(java.util.UUID):java.util.Optional",                        // 查询
            "session(java.lang.String):java.util.Optional",                        // 查询
            "sessions():java.util.List",                                           // 查询
            "switchMode(java.lang.String,com.hydrokarbs.engine.session.MatchModeSelection,"
                    + "com.hydrokarbs.engine.session.Handoff):java.util.concurrent.CompletableFuture",   // 控制
            "modes():java.util.List",                                              // 目录
            "maps():java.util.List",                                               // 目录
            "mode(java.lang.String):java.util.Optional",                           // 目录
            // ── 7.5 增量(v1.1 v2 面 + ★v1.2 v3 面;07 §7.1/19 §3/20 §2;
            //    ★v1.7 删 D4 能力标签 2 行) ──
            "joinSession(java.lang.String,java.util.UUID):"
                    + "com.hydrokarbs.engine.session.JoinResult",                  // D2 成员(★42)
            "leaveSession(java.util.UUID,com.hydrokarbs.engine.session.LeaveReason):void",   // D2 成员(幂等)
            "subscribeSessionEvents(java.util.function.Consumer):"
                    + "com.hydrokarbs.api.EventSubscription",                      // D3 订阅(★43)
            "createSession(com.hydrokarbs.api.SessionRecipe):"
                    + "com.hydrokarbs.api.view.SessionInfo",                       // D14 通道二(★52)
            "registerMap(org.bukkit.plugin.Plugin,com.hydrokarbs.map.MapDefinition):"
                    + "com.hydrokarbs.api.view.MapInfo",                           // ★v1.2 地图注册(★54)
            "unregisterMap(org.bukkit.plugin.Plugin,java.lang.String):void",       // ★v1.2 地图摘除
            // ── ★v1.6 增量(显式注册收敛;24 号修正案,ServicesManager 提供方通道退场) ──
            "registerMode(org.bukkit.plugin.Plugin,"
                    + "com.hydrokarbs.engine.mode.MatchModeProvider):"
                    + "com.hydrokarbs.api.view.MatchModeInfo",                     // 模式显式注册
            "unregisterMode(org.bukkit.plugin.Plugin,java.lang.String):void",      // 模式摘除(回退编排)
            "registerSessionComponents(org.bukkit.plugin.Plugin,java.util.List):void",   // D14 通道一显式面
            "unregisterSessionComponents(org.bukkit.plugin.Plugin):void");         // 贡献摘除(幂等)

    @Test
    void methodSetEqualsFrozenSurface() {
        Set<String> actual = Arrays.stream(MiniGameApi.class.getDeclaredMethods())
                .map(MiniGameApiSurfaceTest::signature)
                .collect(Collectors.toSet());
        assertEquals(FROZEN, actual,
                "MiniGameApi 方法集与冻结清单不符——「只增不改」(07-公共API §7.2;★19)。"
                        + "新增 = 追加清单并单独 PR 说明动机与消费者;删/改签名 = 破坏冻结面,驳回归位");
    }

    @Test
    void apiVersionIsSix() {
        // 版本协商常量冻结(D7;v1.0=1;v1.1=2;v1.2=3;v1.3 维持 3;★v1.4=4;★v1.6=5;★v1.7=6)
        assertEquals(6, MiniGameApi.API_VERSION, "API_VERSION 只增不减(D7;14.36 消费方拒载依据)");
    }

    /** 归一化:方法名(参数类型逗号串,声明序):返回类型(全 Class#getName)。 */
    private static String signature(Method method) {
        String params = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + params + "):" + method.getReturnType().getName();
    }
}
