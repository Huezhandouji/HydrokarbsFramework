package com.hydrokarbs.bridge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * YAML 编解码器(15-适配 §15.2;施工计划 2.2「测试与验收」):readMode 单口——
 * 格式错误 → {@link ConfigException} 带文件名与解析器行号;空文件 → 空配置可用
 * (铁律 1);值类型非法 → 取默认不抛(铁律 2)。离线:零 Bukkit import。
 * (地图文件读口随文件式装载通道删除收敛——地图样例全字段解析测试一并移除。)
 */
class YamlCodecTest {

    @TempDir
    Path tempDir;

    private final YamlCodec codec = new YamlCodec();
    private final List<LogRecord> logs = new ArrayList<>();

    @BeforeEach
    void captureLogs() {
        Logger logger = Logger.getLogger(MatchModeConfigLoader.class.getName());
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
    }

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void malformedYamlFailsWithFileNameAndLine() throws IOException {
        Path file = write("bad-syntax.yml", """
                rounds:
                 oops: [unclosed
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> codec.readMode(file));
        assertTrue(e.getMessage().contains("bad-syntax.yml"),
                "异常消息必须带文件名: " + e.getMessage());
        assertTrue(e.getMessage().contains("line"),
                "格式错误消息应含解析器定位(行号): " + e.getMessage());
    }

    @Test
    void emptyModeFileYieldsUsableEmptyConfig() throws IOException {
        Path file = write("empty.yml", "");
        Map<String, Object> root = codec.readMode(file);
        assertTrue(root.isEmpty(), "空文件 → 空映射(铁律 1)");
        var config = new MatchModeConfigLoader(codec).load(file);
        assertEquals(13, config.getInt("rounds.best-of", 13));
        assertEquals(List.of(), config.getStringList("vote-pool", List.of()));
    }

    @Test
    void nonParsableValueFallsBackToDefaultWithoutThrowing() throws IOException {
        // 铁律 2:值类型与目标不符 → 取默认 + WARN,不抛。
        // 注:文档示例的 -5 属「玩法语义非法」示意,配置层只拦「类型不可解析」
        // (语义校验归消费方,见本子阶段登记);此处以类型非法值覆盖该铁律。
        Path file = write("weird.yml", """
                rounds:
                  best-of: { not: a-number }
                """);
        var config = new MatchModeConfigLoader(codec).load(file);
        assertEquals(13, config.getInt("rounds.best-of", 13));
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                "非法值降级必须记 WARN");
    }
}
