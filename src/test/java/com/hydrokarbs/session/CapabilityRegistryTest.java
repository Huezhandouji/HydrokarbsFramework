package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.hydrokarbs.engine.session.CapabilityRegistry;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;

/**
 * 能力标签机制件测试(2.3 测试表):注册/查询/单查/注销语义、空白串响亮拒绝、
 * 幂等;引擎源码无任何具体标签字面量(机制与词汇分离,框架零标签可运行——
 * 声明标签的模式在标签缺席时被 A2 拒,零标签模式照常运行)。
 */
@Timeout(30)
class CapabilityRegistryTest {

    @Test
    void registerAvailableAndIsAvailable() {
        CapabilityRegistry registry = new CapabilityRegistry();
        assertTrue(registry.available().isEmpty(), "初始为空(框架自身零标签可运行)");

        registry.register("economy");
        registry.register("teleport");

        assertEquals(Set.of("economy", "teleport"), registry.available());
        assertTrue(registry.isAvailable("economy"));
        assertTrue(registry.isAvailable("teleport"));
        assertFalse(registry.isAvailable("absent-tag"));
    }

    @Test
    void registerIsIdempotentAndUnregisterRemoves() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("economy");
        registry.register("economy");                    // 重复注册无副作用
        assertEquals(Set.of("economy"), registry.available());

        registry.unregister("economy");
        assertTrue(registry.available().isEmpty(), "摘除生效");
        assertDoesNotThrow(() -> registry.unregister("economy"), "摘除缺席标签幂等无害");
    }

    @Test
    void blankTagIsRejectedLoudly() {
        CapabilityRegistry registry = new CapabilityRegistry();
        assertThrows(Exception.class, () -> registry.register("  "));
        assertThrows(Exception.class, () -> registry.register(null));
        assertThrows(Exception.class, () -> registry.isAvailable(null));
    }

    /** 引擎源码无任何具体标签字面量:注册/注销调用不携带字符串字面量,词汇由提供方定义。 */
    @Test
    void engineSourcesContainNoConcreteTagLiterals() throws IOException {
        List<String> hits = new java.util.ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/hydrokarbs/engine"))) {
            for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                scanned++;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.contains(".register(\"") || line.contains(".unregister(\"")
                            || line.contains(".isAvailable(\"")) {
                        hits.add(file + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(scanned > 10, "扫描面防哑火(engine/ 文件数): " + scanned);
        assertTrue(hits.isEmpty(), "引擎源码不得出现任何具体标签字面量(机制与词汇分离):\n"
                + String.join("\n", hits));
    }

    /** A2 链路:标签注册后声明该标签的模式可切;缺席时被拒(细节进 detail)。 */
    @Test
    void taggedModeSwitchFollowsTagAvailability() throws Exception {
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("base", 0, 100),
                SwitchHarness.mode("vip", 0, 100, java.util.Set.of("vip-pass"),
                        com.hydrokarbs.engine.mode.MatchModeMeta.DefaultTransition.FALLBACK));
        com.hydrokarbs.engine.session.Session session = harness.createSession("cap", "base");

        var rejected = harness.service.switchMode(session.id(), MatchModeSelection.of("vip"), Handoff.empty())
                .get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(rejected instanceof com.hydrokarbs.engine.session.SwitchResult.Rejected bounced
                        && bounced.detail().contains("vip-pass"),
                "标签缺席时被 A2 拒且 detail 点名标签: " + rejected);

        harness.capabilities.register("vip-pass");
        for (int i = 0; i < 101; i++) {
            session.idleTimers();
        }
        var future = harness.service.switchMode(session.id(),
                new MatchModeSelection("vip", java.util.Optional.empty(), true), Handoff.empty());
        harness.ticker.tickAll();
        harness.ticker.tickAll();
        var allowed = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(allowed instanceof com.hydrokarbs.engine.session.SwitchResult.Succeeded,
                "标签注册后同模式可切");
        assertEquals("vip", session.currentInstance().orElseThrow().modeId());
        assertTrue(harness.service.sessionOf(java.util.UUID.randomUUID()).isEmpty(), "会话查询正常(旁证)");
    }

    /**
     * ★32 会话侧(2.6):会话组件标签进 A2 可用集(进程级 ∪ 本会话)——
     * 进程级零该标签时,挂了组件的会话可切声明该标签的模式;未挂组件的会话被拒
     * 且 detail 列缺失标签(桥接侧「role」标签注册归阶段 3.3 宿主扩展线,不冒充)。
     */
    @Test
    void sessionComponentTagsExtendA2AvailabilityPerSession() throws Exception {
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100),
                SwitchHarness.mode("base", 0, 100),
                SwitchHarness.mode("vip", 0, 100, java.util.Set.of("arena-cap"),
                        com.hydrokarbs.engine.mode.MatchModeMeta.DefaultTransition.FALLBACK));
        List<String> log = new ArrayList<>();

        // 未挂组件的会话:标签缺席 → 拒(detail 点名)
        com.hydrokarbs.engine.session.Session bare = harness.createSession("bare", "base");
        var rejected = harness.service.switchMode(bare.id(), MatchModeSelection.of("vip"), Handoff.empty())
                .get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(rejected instanceof com.hydrokarbs.engine.session.SwitchResult.Rejected bounced
                        && bounced.detail().contains("arena-cap"),
                "未装配会话组件 → REJECTED_BY_TARGET_MODE + detail 列缺失标签: " + rejected);

        // 挂了组件的会话:本会话标签集并入可用集 → 同模式可切(进程级零该标签)
        com.hydrokarbs.engine.session.Session tagged = harness.createSession("tagged", "base",
                List.of(com.hydrokarbs.testkit.SessionProbeComponent.tagged(log, "arena-cap")));
        assertTrue(tagged.sessionComponentCapabilities().contains("arena-cap"), "组件标签进本会话标签集");
        assertFalse(harness.capabilities.isAvailable("arena-cap"), "进程级注册表保持零该标签(并集来源是会话侧)");
        var future = harness.service.switchMode(tagged.id(),
                new MatchModeSelection("vip", java.util.Optional.empty(), true), Handoff.empty());
        harness.ticker.tickAll();
        harness.ticker.tickAll();
        var allowed = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(allowed instanceof com.hydrokarbs.engine.session.SwitchResult.Succeeded,
                "会话组件标签进可用集(∪),声明该标签的模式可开局: " + allowed);
        assertEquals("vip", tagged.currentInstance().orElseThrow().modeId());
    }
}
