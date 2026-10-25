package com.hydrokarbs.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.HandoffView;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.testkit.ProbeComponent;

/**
 * ★18「交接类型安全」首建(2.3 测试表;真键复验属玩法线 5.2):
 * merge 的外部覆盖方向、类型不符 empty 不抛、旧实例写出的键在新实例可读
 * (经真实切换链路)、框架源码零交接键常量(键随主人,框架不写任何键)。
 */
@Timeout(30)
class HandoffContractTest {

    private static final Handoff.Key<String> KEY_A = Handoff.Key.of("ns-a", "value", String.class);
    private static final Handoff.Key<Integer> KEY_INT = Handoff.Key.of("ns-a", "count", Integer.class);
    private static final Handoff.Key<String> KEY_SAME_NAME_INT_Typed
            = Handoff.Key.of("ns-a", "count", String.class);      // 同名不同型(类型漂移)

    /** merge:external 同名键覆盖 outgoing(外部指令优先于旧实例自报,§3.2-B3)。 */
    @Test
    void mergeExternalOverridesOutgoing() {
        Handoff outgoing = Handoff.of(KEY_A, "from-old-instance");
        Handoff external = Handoff.of(KEY_A, "from-admin-command");

        Handoff merged = Handoff.merge(outgoing, external);

        assertEquals(Optional.of("from-admin-command"), merged.get(KEY_A),
                "外部同名键覆盖旧实例自报(方向写反 = 外部指令被旧实例劫持)");
    }

    /** merge:不同名键并集保留双方。 */
    @Test
    void mergeKeepsDisjointKeys() {
        Handoff merged = Handoff.merge(Handoff.of(KEY_A, "a"), Handoff.of(KEY_INT, 7));
        assertEquals(Optional.of("a"), merged.get(KEY_A));
        assertEquals(Optional.of(7), merged.get(KEY_INT));
        assertTrue(merged.has(KEY_A) && merged.has(KEY_INT), "双方键都在");
    }

    /** 类型不符 get 返回 empty 不抛(§14.20-②:防类型漂移炸裂)。 */
    @Test
    void wrongTypeGetReturnsEmptyWithoutThrowing() {
        Handoff handoff = Handoff.of(KEY_INT, 42);
        HandoffView view = handoff;

        Optional<String> mismatched = view.get(KEY_SAME_NAME_INT_Typed);

        assertTrue(mismatched.isEmpty(), "类型不符返回 empty");
        assertEquals(Optional.of(42), view.get(KEY_INT), "同键同型照常可读");
        assertTrue(view.has(KEY_SAME_NAME_INT_Typed), "has 不论类型照常可见");
    }

    /** 真实链路:旧实例 onHandoff 写出的键,切换后在新实例可读(交接袋贯穿)。 */
    @Test
    void oldInstanceHandoffKeysAreReadableInNextInstance() throws Exception {
        List<String> log = new ArrayList<>();
        LinkedHashMap<String, MatchModeComponent.Specification<?>> source = new LinkedHashMap<>();
        source.put("writer", new ProbeComponent.Spec(log).handoffValue("carry-42"));
        LinkedHashMap<String, MatchModeComponent.Specification<?>> empty = new LinkedHashMap<>();
        SwitchHarness harness = new SwitchHarness("fb",
                SwitchHarness.mode("fb", 0, 100, source),
                SwitchHarness.mode("plain", 0, 100, empty));
        com.hydrokarbs.engine.session.Session session = harness.createSession("carry", "fb");
        harness.service.join(session.id(), java.util.UUID.randomUUID());
        for (int i = 0; i < 101; i++) {
            session.idleTimers();
        }
        Handoff externalPayload = Handoff.of(
                Handoff.Key.of("admin", "note", String.class), "admin-note");

        harness.service.switchMode(session.id(),
                new MatchModeSelection("plain", Optional.empty(), true), externalPayload);
        harness.ticker.tickAll();
        harness.ticker.tickAll();

        HandoffView incoming = session.currentInstance().orElseThrow().handoff();
        assertEquals(Optional.of("carry-42"),
                incoming.get(Handoff.Key.of("writer", ProbeComponent.TOKEN_NAME, String.class)),
                "旧实例 onHandoff 写入的键在新实例可读(键随主人)");
        assertEquals(Optional.of("admin-note"),
                incoming.get(Handoff.Key.of("admin", "note", String.class)),
                "外部载荷并入(B3 merge)");
    }

    /** 框架源码零交接键常量:引擎目录不出现任何交接键声明(键由组件/模式/第三方主人声明)。 */
    @Test
    void frameworkSourcesDeclareNoHandoffKeys() throws IOException {
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/hydrokarbs/engine"))) {
            for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                scanned++;
                if (file.getFileName().toString().equals("Handoff.java")
                        || file.getFileName().toString().equals("HandoffView.java")) {
                    continue;       // 载荷机制本体:定义 Key 类型本身不是"声明键"
                }
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.contains("Handoff.Key.of") || line.contains("static final Handoff.Key")) {
                        hits.add(file + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(scanned > 10, "扫描面防哑火(engine/ 文件数): " + scanned);
        assertTrue(hits.isEmpty(), "框架引擎源码不得声明任何交接键(键随主人):\n"
                + String.join("\n", hits));
    }
}
