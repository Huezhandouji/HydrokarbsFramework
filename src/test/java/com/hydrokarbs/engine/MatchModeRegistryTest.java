package com.hydrokarbs.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.ComponentDependencyException;
import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.mode.MatchModeProvider;
import com.hydrokarbs.engine.mode.MatchModeRegistry;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.map.MapDefinition;
import com.hydrokarbs.testkit.TestComponent;

/**
 * 模式注册表注册闸(施工计划 2.2「测试与验收」;§12.2/§2.6):
 * 假工厂经 register 进目录并可 require;同 id 重复 → 响亮 ISE;
 * 依赖校验失败 → 跳过 + SEVERE、其余照常;forMap 按 tag 交集;
 * 未知 id require 抛。discover() 的 ServiceLoader 半边以
 * 「SPI 清单存在且内容为空」代测(离线零 Bukkit);ServicesManager 半边
 * 注释预留 7.2(登记施工偏差)。离线:零 Bukkit import/静态调用。
 */
class MatchModeRegistryTest {

    private final List<LogRecord> logs = new ArrayList<>();
    private final List<String> hookLog = new ArrayList<>();
    private MatchModeRegistry registry;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger(MatchModeRegistryTest.class.getName() + "-"
                + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        // 配置源:全回落缺省(装载链已由 ConfigRepositoryTest 覆盖,此处只测注册闸)
        MatchModeConfig fallback = new MatchModeConfig() {
            @Override public int getInt(String path, int def) { return def; }
            @Override public String getString(String path, String def) { return def; }
            @Override public List<String> getStringList(String path, List<String> def) { return def; }
            @Override public List<String> keys(String section) { return List.of(); }
        };
        registry = new MatchModeRegistry(id -> fallback, logger);
    }

    // ── 假工厂 ───────────────────────────────────────────────────

    /** 依赖自满足的假工厂:组件表全部声明无依赖的假组件。 */
    private static MatchModeProvider provider(String id, Set<String> mapTags,
                                              MatchModeComponent.Specification<?>... specs) {
        return new MatchModeProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public MatchMode build(MatchModeConfig cfg) {
                MatchMode.Builder builder = new MatchMode.Builder(id)
                        .mapTags(mapTags)
                        .meta(new MatchModeMeta(Set.of(), false, 0, 100,
                                MatchModeMeta.DefaultTransition.STAY));
                int index = 1;
                for (MatchModeComponent.Specification<?> spec : specs) {
                    builder.addComponent("c" + index++, spec);
                }
                return builder.build();
            }
        };
    }

    /** 无人提供的组件类型(制造「依赖校验失败」的坏模板)。 */
    private abstract static class Unresolved extends MatchModeComponent {

        Unresolved(String id, MatchModeInstanceServices svc) {
            super(id, svc);
        }
    }

    private boolean hasSevere() {
        return logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE);
    }

    // ── 用例 ─────────────────────────────────────────────────────

    @Test
    void registeredProviderIsRetrievableByRequire() {
        registry.register(provider("alpha", Set.of("standard"), TestComponent.spec(hookLog)));
        MatchMode mode = registry.require("alpha");
        assertEquals("alpha", mode.id());
        assertSame(mode, registry.all().iterator().next());
    }

    @Test
    void duplicateIdFailsLoudlyAndKeepsFirstArrival() {
        registry.register(provider("alpha", Set.of("a")));
        assertThrows(IllegalStateException.class,
                () -> registry.register(provider("alpha", Set.of("b"))));
        assertEquals(1, registry.all().size());
        assertEquals(Set.of("a"), registry.require("alpha").mapTags(), "先到者保留,不静默覆盖");
    }

    @Test
    void dependencyFailureIsSkippedWithSevereWhileOthersRegister() {
        registry.register(provider("bad", Set.of("x"),
                TestComponent.requiring(hookLog, Unresolved.class)));   // 无人提供 → 坏模板
        registry.register(provider("good", Set.of("x"), TestComponent.spec(hookLog)));

        assertThrows(NoSuchElementException.class, () -> registry.require("bad"));
        assertTrue(hasSevere(), "依赖校验失败必须 SEVERE(§2.6)");
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE
                && String.valueOf(r.getMessage()).contains("bad")), "SEVERE 点名坏模式 id");
        assertEquals(1, registry.all().size());
        assertEquals("good", registry.require("good").id());
    }

    @Test
    void dependencyExceptionTypeIsComponentDependencyException() {
        MatchMode bad = new MatchMode.Builder("probe")
                .meta(new MatchModeMeta(Set.of(), false, 0, 100, MatchModeMeta.DefaultTransition.STAY))
                .addComponent("needs", TestComponent.requiring(hookLog, Unresolved.class))
                .build();
        assertThrows(ComponentDependencyException.class, bad::verifyDependencies);
    }

    @Test
    void requireUnknownIdThrowsLoudly() {
        NoSuchElementException e = assertThrows(NoSuchElementException.class,
                () -> registry.require("ghost"));
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    void forMapFiltersByTagIntersection() {
        registry.register(provider("m-std", Set.of("standard")));
        registry.register(provider("m-wide", Set.of("standard", "large")));
        registry.register(provider("m-coop", Set.of("cooperative")));

        MapDefinition standardMap = new MapDefinition("map1", "w", Set.of("standard", "arena"), Map.of());
        List<String> matched = registry.forMap(standardMap).stream().map(MatchMode::id).toList();
        assertEquals(List.of("m-std", "m-wide"), matched);

        MapDefinition emptyTags = new MapDefinition("map2", "w", Set.of(), Map.of());
        assertTrue(registry.forMap(emptyTags).isEmpty(), "空交集 = 不匹配(合法,不抛)");
    }

    @Test
    void zeroModesIsLegalState() {
        assertTrue(registry.all().isEmpty(), "0 个模式注册属合法状态(不打错误)");
        assertFalse(hasSevere());
    }

    @Test
    void discoverOnEmptySpiKeepsRegistryEmptyAndLogsInit() {
        registry.discover();   // ServiceLoader 半边:SPI 清单为空 → 无工厂可发现(离线可跑)
        assertTrue(registry.all().isEmpty());
        assertNotNull(logs.stream()
                .filter(r -> r.getLevel() == Level.INFO)
                .map(LogRecord::getMessage)
                .filter(m -> String.valueOf(m).contains("模式注册表初始化完成"))
                .findFirst()
                .orElse(null), "注册表初始化行必须出现(0 个模式属正常)");
    }

    /** discover() 的 ServiceLoader 半边代测:SPI 文件存在且内容为空(只注释)。 */
    @Test
    void spiManifestExistsAndIsEmpty() throws IOException {
        Path spi = Path.of("src/main/resources/META-INF/services",
                "com.hydrokarbs.engine.mode.MatchModeProvider");
        assertTrue(Files.isRegularFile(spi), "SPI 资源文件必须存在(§12.1/§13.1-#7): " + spi);
        List<String> entries = Files.readAllLines(spi, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertTrue(entries.isEmpty(),
                "框架基线 SPI 清单应为空(玩法线施工阶段追加工厂行);实为: " + entries);
    }
}
