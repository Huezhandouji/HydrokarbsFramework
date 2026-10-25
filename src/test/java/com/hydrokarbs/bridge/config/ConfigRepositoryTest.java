package com.hydrokarbs.bridge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 配置仓库(施工计划 2.2「测试与验收」;§15.2 文件分工/铁律 3):
 * {@code saveDefaults} 幂等(二次调用不覆盖已存在文件);
 * {@code loadAll} 对空目录返回空注册表不抛;单文件损坏只跳过该文件(SEVERE 点名);
 * 模式配置缓存与 {@code ConfigView} 主配置读取。
 * 离线:默认资源源以临时目录实现注入,零 Bukkit 触点。
 */
class ConfigRepositoryTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path defaultsDir;

    private final List<LogRecord> logs = new ArrayList<>();
    private ConfigRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Logger logger = Logger.getLogger(ConfigRepositoryTest.class.getName() + "-"
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
        Files.writeString(defaultsDir.resolve("config.yml"),
                "config-version: 2\nengine:\n  tick-period: 3\n", StandardCharsets.UTF_8);
        // 默认资源源:临时目录直读(替代插件 jar 读取——生产装配的唯一 Bukkit 触点)
        repository = new ConfigRepository(dataDir, name -> {
            Path file = defaultsDir.resolve(name);
            return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        }, logger);
    }

    private boolean hasSevereNaming(Path file) {
        return logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE
                && String.valueOf(r.getMessage()).contains(file.getFileName().toString()));
    }

    @Test
    void saveDefaultsIsIdempotentAndNeverOverwrites() throws IOException {
        repository.saveDefaults();
        Path config = dataDir.resolve("config.yml");
        assertTrue(Files.isRegularFile(config), "首次释放默认 config.yml");

        // 用户改过配置 → 二次启动不覆盖
        Files.writeString(config, "config-version: 2\nengine:\n  tick-period: 99\n",
                StandardCharsets.UTF_8);
        repository.saveDefaults();
        assertEquals("99", Files.readAllLines(config, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains("tick-period")).findFirst().orElse("")
                .split(":")[1].trim(), "已存在的用户配置不得被默认资源覆盖");
    }

    @Test
    void loadAllOnEmptyDirsYieldsEmptyStateWithoutThrowing() throws IOException {
        repository.loadAll();
        assertTrue(repository.maps().all().isEmpty());
        assertTrue(repository.modeConfig("anything").getStringList("pool", List.of()).isEmpty());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                "主配置缺席应 WARN 后按默认运行");
    }

    @Test
    void corruptedMapFileIsSkippedWithSevereWhileOthersLoad() throws IOException {
        Files.createDirectories(dataDir.resolve("maps"));
        Files.writeString(dataDir.resolve("maps/good.yml"), """
                id: good_map
                world: w
                tags: [pvp]
                """, StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("maps/broken.yml"), """
            id: [unclosed
            """, StandardCharsets.UTF_8);

        repository.loadAll();

        Path broken = dataDir.resolve("maps/broken.yml");
        assertTrue(hasSevereNaming(broken), "坏文件必须 SEVERE 点名(铁律 3)");
        assertEquals(1, repository.maps().all().size());
        assertTrue(repository.maps().byId("good_map").isPresent());
    }

    @Test
    void corruptedModeFileIsSkippedWhileOthersLoad() throws IOException {
        Files.createDirectories(dataDir.resolve("modes"));
        Files.writeString(dataDir.resolve("modes/alpha.yml"),
                "vote-seconds: 45\n", StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("modes/beta.yml"),
                "key: {oops\n", StandardCharsets.UTF_8);

        repository.loadAll();

        assertEquals(45, repository.modeConfig("alpha").getInt("vote-seconds", 30));
        assertTrue(hasSevereNaming(dataDir.resolve("modes/beta.yml")));
    }

    @Test
    void unloadableModeIdYieldsEmptyConfig() throws IOException {
        Files.createDirectories(dataDir.resolve("modes"));
        var config = repository.modeConfig("ghost");
        assertEquals(30, config.getInt("vote-seconds", 30));
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING));
    }

    @Test
    void configViewReadsMainConfigLazily() throws IOException {
        repository.saveDefaults();                          // 释放默认(tick-period: 3)
        repository.loadAll();
        assertEquals(3, repository.get("engine.tick-period", 1));
        assertEquals("def", repository.get("absent.key", "def"));

        // /reload 语义:底层文件换新 → 再次 loadAll → get 读到新值(★39 惰性)
        Files.writeString(dataDir.resolve("config.yml"),
                "engine:\n  tick-period: 7\n", StandardCharsets.UTF_8);
        repository.loadAll();
        assertEquals(7, repository.get("engine.tick-period", 1));
    }

    @Test
    void loadAllIsReentrantForHotReload() throws IOException {
        Files.createDirectories(dataDir.resolve("maps"));
        Files.writeString(dataDir.resolve("maps/m1.yml"), """
                id: m1
                world: w
                tags: [a]
                """, StandardCharsets.UTF_8);
        repository.loadAll();
        assertTrue(repository.maps().byId("m1").isPresent());

        Files.delete(dataDir.resolve("maps/m1.yml"));
        repository.loadAll();
        assertFalse(repository.maps().byId("m1").isPresent(), "全量替换:旧文件消失即摘除");
    }
}
