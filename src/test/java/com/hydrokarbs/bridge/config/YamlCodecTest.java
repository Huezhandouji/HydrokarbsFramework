package com.hydrokarbs.bridge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hydrokarbs.engine.port.ResetStrategy;
import com.hydrokarbs.map.NamedRegion;

/**
 * YAML 编解码器(15-适配 §15.2;施工计划 2.2「测试与验收」):
 * 合法地图样例解析字段全对(含圆柱/球形区域、交互点、还原策略);
 * 缺 id → {@link ConfigException} 带文件名;值类型非法 → 取默认不抛(铁律 2);
 * 空文件 → 空配置可用(铁律 1)。离线:零 Bukkit import。
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

    /** 合法地图样例(§15.2 地图文件形状:三形区域、交互点、边界、元数据、还原策略)。 */
    private static final String CROSSFIRE = """
            id: crossfire
            display-name: 交叉火力
            world: crossfire_tpl
            tags: [pvp, medium]
            bounds:
              min: [0, 60, 0]
              max: [200, 120, 200]
            spawns:
              lobby:
                - { x: 10.5, y: 70, z: 12.5, yaw: 90 }
                - { x: 12, y: 70, z: 12 }
              attacker:
                - { x: 100, y: 70, z: 10 }
            regions:
              mid-bunker:
                type: cuboid
                min: [50, 60, 50]
                max: [80, 90, 80]
                tags: [core]
              spawn-ring:
                type: cylinder
                center: [100, 70, 100]
                radius: 24.5
                height: 8
                tags: [guard]
              ritual:
                type: sphere
                center: [100, 80, 100]
                radius: 12
                tags: [core]
            interact-points:
              altar:
                pos: [100, 71, 100]
                radius: 2.5
                tag: shrine
                anchor: world
            metadata:
              min-players: 4
              authors: [Alice, Bob]
            reset-strategy: TEMPLATE_RESTORE
            """;

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void crossfireSampleParsesEveryField() throws IOException {
        var map = codec.readMap(write("crossfire.yml", CROSSFIRE));

        assertEquals("crossfire", map.id());
        assertEquals("交叉火力", map.displayName());
        assertEquals("crossfire_tpl", map.world());
        assertEquals(Set.of("pvp", "medium"), map.tags());
        assertEquals(ResetStrategy.TEMPLATE_RESTORE, map.resetStrategy());

        assertNotNull(map.bounds());
        assertEquals(0.0, map.bounds().min()[0]);
        assertEquals(120.0, map.bounds().max()[1]);

        assertEquals(2, map.spawns("lobby").size());
        assertEquals(90f, map.spawns("lobby").get(0).yaw());
        assertEquals(1, map.spawns("attacker").size());
        assertEquals(100.0, map.spawns("attacker").get(0).x());

        assertEquals(3, map.regions().size());
        var cuboid = map.regions().byId("mid-bunker").orElseThrow();
        assertEquals(NamedRegion.RegionType.CUBOID, cuboid.type());
        assertTrue(cuboid.contains(new double[] { 65, 75, 65 }));
        assertTrue(cuboid.tags().contains("core"));
        var cylinder = map.regions().byId("spawn-ring").orElseThrow();
        assertEquals(NamedRegion.RegionType.CYLINDER, cylinder.type());
        assertTrue(cylinder.contains(new double[] { 110, 72, 100 }));
        assertTrue(!cylinder.contains(new double[] { 110, 80, 100 }));   // 超出高度
        var sphere = map.regions().byId("ritual").orElseThrow();
        assertEquals(NamedRegion.RegionType.SPHERE, sphere.type());
        assertTrue(sphere.contains(new double[] { 100, 85, 100 }));
        assertTrue(!sphere.contains(new double[] { 100, 95, 100 }));
        assertEquals(2, map.regions().byTag("core").size());

        assertEquals(1, map.interactPoints().size());
        var point = map.interactPoints().get(0);
        assertEquals("altar", point.id());
        assertEquals(2.5, point.radius());
        assertEquals("shrine", point.tag());
        assertEquals("world", point.anchor());

        assertEquals(4, ((Number) map.metadata().get("min-players")).intValue());
        assertEquals(List.of("Alice", "Bob"), map.metadata().get("authors"));
    }

    @Test
    void missingIdFailsLoudlyWithFileName() throws IOException {
        Path file = write("broken.yml", """
                world: w
                tags: [a]
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> codec.readMap(file));
        assertTrue(e.getMessage().contains("broken.yml"),
                "异常消息必须带文件名: " + e.getMessage());
    }

    @Test
    void malformedYamlFailsWithFileNameAndLine() throws IOException {
        Path file = write("bad-syntax.yml", """
                id: x
                 oops: [unclosed
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> codec.readMap(file));
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
