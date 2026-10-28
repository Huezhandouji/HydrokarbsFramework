package com.hydrokarbs.component.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.EngineTicker;
import com.hydrokarbs.engine.event.SessionEvent;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.PlatformPort;
import com.hydrokarbs.engine.session.DissolveReason;
import com.hydrokarbs.engine.session.JoinResult;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionPolicy;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SessionServiceImpl;
import com.hydrokarbs.engine.session.SessionSpec;
import com.hydrokarbs.map.MapRegistry;
import com.hydrokarbs.testkit.FakeWorldPort;
import com.hydrokarbs.testkit.OfflineAssembly;

/**
 * ★31 空场策略可插拔(§16.2;17-D11;06-共享组件 §6.4):
 * 假时钟(tick 推进,不吃真实时间)+ 成员事件驱动——到点
 * {@code requestDissolve(DissolveReason.EMPTY)} <b>恰一次</b>;成员回归<b>重置计时</b>;
 * 不挂(空组件表)= <b>永不触发</b>(「不挂 = 默认」,镜像入场控制)。
 * 原内核空场骨架的断言(2.1 SessionPolicyTest)按 D11 迁入本测试(改按 tick 量纲)。
 *
 * <p>自足离线装配(不经 session 测试包的包私有基座):真注册表/真地图目录/
 * 世界假件/真会话服务/真节拍器(不 start,直调驱动)。
 */
class EmptyArenaComponentTest {

    /** 3 秒 = 60 tick(标准节拍 20 tps;折算口径见组件类注,★ 待裁决③)。 */
    private static final long AFTER_TICKS = 60L;

    /** createArena 两拍驱动后空场计数已推进 2(首切实例就位的两拍各计一次)。 */
    private static final long HEAD_START_TICKS = 2L;

    private static final class Harness {
        final SessionServiceImpl service;
        final EngineTicker ticker;

        Harness() {
            MatchMode probeMode = OfflineAssembly.mode("probe-mode", new java.util.LinkedHashMap<>());
            com.hydrokarbs.engine.port.ModeLookup modes = new com.hydrokarbs.engine.port.ModeLookup() {
                @Override
                public MatchMode require(String modeId) {
                    if ("probe-mode".equals(modeId)) {
                        return probeMode;
                    }
                    throw new IllegalStateException("未注册: " + modeId);
                }

                @Override
                public java.util.Collection<MatchMode> all() {
                    return List.of(probeMode);
                }
            };
            // 自理临时模板根 + 注册式地图(★文件式装载通道已删,造数走 MapTestKit 注册 fixture)
            java.nio.file.Path templatesRoot = com.hydrokarbs.testkit.MapTestKit.newTemplatesRoot();
            MapRegistry maps = new MapRegistry(templatesRoot);
            com.hydrokarbs.testkit.MapTestKit.registered(maps, templatesRoot, OfflineAssembly.map());
            service = new SessionServiceImpl.Builder()
                    .modes(modes)
                    .worlds(FakeWorldPort.immediate())
                    .maps(maps)
                    .configSource(id -> OfflineAssembly.cfg())
                    .policy(SessionPolicy.defaults(SessionPolicy.Topology.ARENA))
                    .fallbackModeId("probe-mode")
                    .engineServices(new EngineServices(noopPlatform()))
                    .mainExecutor(Runnable::run)
                    .hubRelocator(members -> { })
                    .safeStateCleaner(members -> { })
                    .build();
            ticker = new EngineTicker(null, service);   // Plugin 位置传 null:不 start,直调
        }

        Session createArena(String id, long afterTicks) {
            Duration after = Duration.ofMillis(afterTicks * 50L);
            Session session = service.create(new SessionSpec(new SessionId(id), "probe-mode", List.of(),
                    List.of(new EmptyArenaComponent.Spec(after))));
            ticker.tickAll();
            ticker.tickAll();   // 首切实例就位(空场计时与实例有无无关)
            return session;
        }

        Session createBare(String id) {
            Session session = service.create(new SessionSpec(new SessionId(id), "probe-mode", List.of()));
            ticker.tickAll();
            ticker.tickAll();
            return session;
        }

        List<SessionEvent.SessionDissolved> dissolvedEvents(Session session) {
            List<SessionEvent.SessionDissolved> dissolved = new ArrayList<>();
            session.events().subscribe(SessionEvent.class, event -> {
                if (event instanceof SessionEvent.SessionDissolved d) {
                    dissolved.add(d);
                }
            });
            return dissolved;
        }
    }

    /** 无操作平台口(later 不被测试触达;Bukkit 位置传 null)。 */
    private static PlatformPort noopPlatform() {
        return new PlatformPort() {
            @Override
            public java.util.logging.Logger logger() {
                return java.util.logging.Logger.getLogger("empty-arena-test");
            }

            @Override
            public org.bukkit.plugin.Plugin plugin() {
                return null;
            }

            @Override
            public AutoCloseable scheduleOnce(long delayTicks, Runnable task) {
                return () -> { };
            }
        };
    }

    /** Duration → tick 折算口径(§15.2 默认 300s = 6000 tick)。 */
    @Test
    void durationFoldsToTicks() {
        assertEquals(6000L, new EmptyArenaComponent.Spec(Duration.ofSeconds(300)).afterTicks(),
                "300 秒按 20tps 折算 6000 tick(折算基准 50ms/tick,类注登记)");
        assertEquals(20L, new EmptyArenaComponent.Spec(Duration.ofSeconds(1)).afterTicks());
    }

    /** 空场到点恰一次:差 1 tick 不触发;到点触发解散恰一次;此后拍零成本。 */
    @Test
    void emptyArenaDissolvesExactlyOnceAtConfiguredTicks() {
        Harness harness = new Harness();
        Session session = harness.createArena("a1", AFTER_TICKS);
        List<SessionEvent.SessionDissolved> dissolved = harness.dissolvedEvents(session);

        // 推进到 差 1 tick(计数 = AFTER_TICKS - 1):createArena 已计 2,再推 AFTER-3
        for (int i = 0; i < AFTER_TICKS - HEAD_START_TICKS - 1; i++) {
            harness.ticker.tickAll();
        }
        assertTrue(dissolved.isEmpty(), "差 1 tick 未到点不触发");
        assertTrue(harness.service.session(session.id()).isPresent(), "会话仍存活");

        harness.ticker.tickAll();   // 到点
        assertEquals(1, dissolved.size(), "到点恰触发一次解散");
        assertEquals(DissolveReason.EMPTY, dissolved.get(0).reason(), "解散原因 = EMPTY");

        for (int i = 0; i < 40; i++) {
            harness.ticker.tickAll();
        }
        assertEquals(1, dissolved.size(), "恰一次(重复触发被吸收)");
        assertTrue(harness.service.session(session.id()).isEmpty(), "会话已解散摘除");
    }

    /** 成员回归即重置:有人在场永不触发;再空场从头起算满时长才触发。 */
    @Test
    void memberReturnResetsEmptyTimer() {
        Harness harness = new Harness();
        Session session = harness.createArena("a2", AFTER_TICKS);
        List<SessionEvent.SessionDissolved> dissolved = harness.dissolvedEvents(session);
        UUID member = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            harness.ticker.tickAll();   // 空场计到 22(有人在场前)
        }
        JoinResult joined = harness.service.join(session.id(), member);
        assertTrue(joined instanceof JoinResult.Joined, "成员加入名册");
        for (int i = 0; i < 80; i++) {
            harness.ticker.tickAll();   // 有人在场:计时重置、永不触发(远超时长)
        }
        assertTrue(dissolved.isEmpty(), "成员回归即重置,有人在场不触发");

        harness.service.leave(member, com.hydrokarbs.engine.session.LeaveReason.KICK);   // 立即移除
        for (int i = 0; i < AFTER_TICKS - 1; i++) {
            harness.ticker.tickAll();   // 重新起算:差 1 tick
        }
        assertTrue(dissolved.isEmpty(), "重置后重新起算,差 1 tick 不触发");
        harness.ticker.tickAll();       // 满时长
        assertEquals(1, dissolved.size(), "重新起算后满时长才触发");
        assertEquals(DissolveReason.EMPTY, dissolved.get(0).reason());
    }

    /** 不挂 = 永不触发(rotation 拓扑空组件表,「不挂 = 默认」)。 */
    @Test
    void bareSessionNeverDissolves() {
        Harness harness = new Harness();
        Session session = harness.createBare("r1");
        List<SessionEvent.SessionDissolved> dissolved = harness.dissolvedEvents(session);

        for (int i = 0; i < 200; i++) {
            harness.ticker.tickAll();
        }

        assertTrue(dissolved.isEmpty(), "不挂空场组件 = 永不解散(常驻会话零配置哲学)");
        assertTrue(harness.service.session(session.id()).isPresent());
    }
}
