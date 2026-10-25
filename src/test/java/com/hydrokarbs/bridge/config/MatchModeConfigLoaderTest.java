package com.hydrokarbs.bridge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 模式配置装载器(施工计划 2.2「测试与验收」;§15.2 铁律 1/2):
 * 模式 yml 缺文件 → 空 config + WARN;键非法 → 默认值 + WARN;
 * {@code getStringList} 对标量值降级为单元素列表;{@code keys} 列节键。
 * 离线:零 Bukkit import。
 */
class MatchModeConfigLoaderTest {

    @TempDir
    Path tempDir;

    private final YamlCodec codec = new YamlCodec();
    private final List<LogRecord> logs = new ArrayList<>();
    private MatchModeConfigLoader loader;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger(MatchModeConfigLoaderTest.class.getName() + "-"
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
        loader = new MatchModeConfigLoader(codec, logger);
    }

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private boolean warned() {
        return logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING);
    }

    @Test
    void missingFileYieldsEmptyConfigWithWarning() {
        var config = loader.load(tempDir.resolve("ghost.yml"));
        assertEquals(30, config.getInt("vote-seconds", 30));
        assertEquals("done", config.getString("kick-message", "done"));
        assertEquals(List.of("a"), config.getStringList("pool", List.of("a")));
        assertTrue(warned(), "缺文件必须 WARN(铁律 1)");
    }

    @Test
    void nonParsableKeyFallsBackToDefaultWithWarning() throws IOException {
        Path file = write("mode.yml", """
                vote-seconds: banana
                banner: [1, 2]
                """);
        var config = loader.load(file);
        assertEquals(30, config.getInt("vote-seconds", 30));
        assertEquals("hi", config.getString("banner", "hi"));      // 列表值取字符串 → 降级
        assertTrue(warned(), "非法值降级必须 WARN(铁律 2)");
    }

    @Test
    void intAsStringIsParsed() throws IOException {
        Path file = write("mode.yml", "vote-seconds: \"45\"");
        assertEquals(45, loader.load(file).getInt("vote-seconds", 30));
    }

    @Test
    void scalarDegradesToSingleElementList() throws IOException {
        Path file = write("mode.yml", """
                pool: solo-entry
                names: [alpha, beta]
                """);
        var config = loader.load(file);
        assertEquals(List.of("solo-entry"), config.getStringList("pool", List.of()));
        assertEquals(List.of("alpha", "beta"), config.getStringList("names", List.of()));
    }

    @Test
    void missingListKeyReturnsDefault() throws IOException {
        Path file = write("mode.yml", "other: 1");
        assertEquals(List.of("x", "y"), loader.load(file).getStringList("pool", List.of("x", "y")));
    }

    @Test
    void keysListsSectionKeysInFileOrder() throws IOException {
        Path file = write("mode.yml", """
                vote-pool:
                  first: 1
                  second: 2
                  third: 3
                """);
        var config = loader.load(file);
        assertEquals(List.of("first", "second", "third"), config.keys("vote-pool"));
        assertEquals(List.of(), config.keys("absent"));
    }

    @Test
    void nestedPathLookup() throws IOException {
        Path file = write("mode.yml", """
                rounds:
                  best-of: 7
                  overtime:
                    enabled: true
                """);
        var config = loader.load(file);
        assertEquals(7, config.getInt("rounds.best-of", 13));
        assertEquals("true", config.getString("rounds.overtime.enabled", "false"));
        assertEquals(List.of("enabled"), config.keys("rounds.overtime"));
        assertEquals(List.of(), config.keys("rounds.best-of"));   // 叶子不是节
    }
}
