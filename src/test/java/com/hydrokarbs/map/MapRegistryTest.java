package com.hydrokarbs.map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.port.ResetStrategy;
import com.hydrokarbs.testkit.MapTestKit;

/**
 * 地图目录(施工计划 2.2「测试与验收」;§15.2 引用原子替换;★单注册通道收敛;
 * ★v1.8 去模板根新基准):{@code register}/{@code unregister} 语义——注册保序
 * (目录顺序 = 注册序)、路径派生 id 查重响亮抛点名先到者(同一路径再注册必撞)、
 * 世界目录缺失/非目录响亮抛带完整绝对路径、摘除幂等 no-op;{@code byId}/
 * {@code byTag}/{@code all} 查询;摘除后持有旧快照者不受影响(不可变快照语义);
 * 注册保存同一不可变声明实例(词料容器构造即冻结,注册后不存在改动通道);无参构造为唯一
 * 构造口(模板根注入口已随去模板根删除,反射守卫)。离线:零 Bukkit import,
 * 世界目录落盘走 MapTestKit fixture。
 */
class MapRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyRegistryQueriesReturnEmpty() {
        MapRegistry registry = new MapRegistry();
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.byId("nothing").isEmpty());
        assertTrue(registry.byTag("any").isEmpty());
    }

    @Test
    void registerKeepsOrderAndQueriesWork() {
        MapRegistry registry = new MapRegistry();
        MapTestKit.registered(registry, tempDir, "m1", "pvp", "small");
        MapTestKit.registered(registry, tempDir, "m2", "pvp");
        MapTestKit.registered(registry, tempDir, "m3", "coop");

        assertEquals("m1", registry.byId("m1").orElseThrow().id(), "byId 命中(路径派生 id)");
        assertTrue(registry.byId("ghost").isEmpty());

        assertEquals(2, registry.byTag("pvp").size());
        assertEquals(1, registry.byTag("coop").size());
        assertTrue(registry.byTag("nothing").isEmpty());

        assertEquals(3, registry.all().size());
        assertEquals(List.of("m1", "m2", "m3"), registry.all().stream().map(MapDefinition::id).toList(),
                "目录顺序 = 注册序(先注册者在前)");
    }

    @Test
    void byTagKeepsRegistrationOrder() {
        MapRegistry registry = new MapRegistry();
        MapTestKit.registered(registry, tempDir, "m2", "tag");
        MapTestKit.registered(registry, tempDir, "m1", "tag");

        assertEquals(List.of("m2", "m1"), registry.byTag("tag").stream().map(MapDefinition::id).toList(),
                "byTag 过滤保注册序,不重排");
    }

    @Test
    void worldDirectoryMustExistAndBeDirectoryWithAbsolutePathInMessage() {
        MapRegistry registry = new MapRegistry();
        // 缺失:未落盘即注册 → 响亮抛,消息带完整绝对路径(20-D3 ★v1.8 口径)
        Path missing = tempDir.resolve("ghost_map");
        MapDefinition ghost = MapTestKit.declaration(missing, Set.of("tag"));
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                () -> registry.register(new Object(), ghost),
                "世界目录缺失 → 响亮抛(20-D3;14.39:注册期校验才有意义)");
        assertTrue(absent.getMessage().contains("ghost_map"), "点名地图 id: " + absent.getMessage());
        assertTrue(absent.getMessage().contains(missing.toAbsolutePath().toString()),
                "带完整绝对路径(★v1.8 detail 口径): " + absent.getMessage());

        // 非目录:同路径是个文件 → 同一响亮闸(路径存在但非目录)
        assertDoesNotThrow(() -> Files.writeString(missing, "not-a-directory"),
                "前置:把同路径写成文件");
        assertTrue(Files.isRegularFile(missing), "前置:路径已存在但为文件");
        IllegalArgumentException notDir = assertThrows(IllegalArgumentException.class,
                () -> registry.register(new Object(), ghost),
                "非目录(文件)→ 响亮抛(20-D3)");
        assertTrue(notDir.getMessage().contains(missing.toAbsolutePath().toString()),
                "非目录拒绝同样带绝对路径: " + notDir.getMessage());

        // 框架照常(★55:拒绝不留半注册态)
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void duplicateIdAgainstRegisteredMapNamesFirstArriver() {
        MapRegistry registry = new MapRegistry();
        Object first = new Object();
        MapTestKit.registered(registry, first, tempDir, "shared", "pvp");

        // 同一路径(规范化后)再注册 → id 派生同源,必撞查重闸(★v1.8);
        // 后来者带不同词料(标签/策略)以证明目录保留的是先到者声明
        MapDefinition latecomer = new MapDefinition(tempDir.resolve("shared"),
                ResetStrategy.TEMPLATE_RESTORE,
                new AttributeBag.Builder().put(MapDefinition.TAGS, Set.of("coop")).build());

        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> registry.register(new Object(), latecomer),
                "重复 id(同一路径)→ 同一闸响亮抛(同一注册通道,20-D3)");
        assertTrue(duplicate.getMessage().contains("shared")
                && duplicate.getMessage().contains("先到者"), "点名先到者与其来源: " + duplicate.getMessage());
        assertEquals(1, registry.all().size(), "先到者保留,后来者不入目录");
        assertEquals("pvp", registry.byId("shared").orElseThrow().tags().iterator().next(),
                "目录中的正式条目仍是先到者声明");
    }

    @Test
    void registeredDeclarationIsFrozenAndHasNoMutationChannel() throws IOException {
        MapRegistry registry = new MapRegistry();
        // 词料在声明装配期一次写入、构造即冻结:注册保存同一声明实例,
        // 但 AttributeBag 无公开写口——注册后不存在改动声明内容的通道
        Map<AttributeBag.Key<Set<String>>, Set<String>> source = new HashMap<>();
        source.put(MapDefinition.TAGS, Set.of("before"));
        AttributeBag.Builder builder = new AttributeBag.Builder();
        source.forEach(builder::put);
        Path dir = tempDir.resolve("frozen");
        Files.createDirectories(dir);                   // 注册期校验要求目录先落盘
        MapDefinition declared = new MapDefinition(dir, builder.build());
        registry.register(new Object(), declared);

        source.put(MapDefinition.TAGS, Set.of("after")); // 装配后改来源 Map:容器不跟随

        MapDefinition stored = registry.byId("frozen").orElseThrow();
        assertSame(declared, stored, "目录保存同一声明实例(交成品不交工厂,20-D1)");
        assertSame(declared.metadata(), stored.metadata(), "词料容器同引用(声明即冻结)");
        assertEquals(Set.of("before"), stored.tags(),
                "读值不受来源 Map 后续改动影响(容器构造即拷贝冻结)");
    }

    @Test
    void unregisterRemovesAndIsIdempotentNoOp() {
        MapRegistry registry = new MapRegistry();
        Object provider = new Object();
        MapTestKit.registered(registry, provider, tempDir, "m1", "pvp");

        assertTrue(registry.unregister(provider, "m1"), "注册式摘除成功(20-D4)");
        assertTrue(registry.byId("m1").isEmpty(), "摘除后目录移除");
        assertFalse(registry.unregister(provider, "m1"), "已不在目录 = 幂等 no-op");
        assertFalse(registry.unregister(new Object(), "never_registered"),
                "未知 id = 幂等 no-op");
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void oldSnapshotHolderUnaffectedByUnregister() {
        MapRegistry registry = new MapRegistry();
        MapTestKit.registered(registry, tempDir, "m1", "tag-a");
        MapDefinition oldSnapshot = registry.byId("m1").orElseThrow();

        assertTrue(registry.unregister(new Object(), "m1"));

        // 持有旧快照者不受摘除影响:record 纯静态值,旧引用照常可读(★v1.8 新形状)
        assertEquals("m1", oldSnapshot.id());
        assertEquals(tempDir.resolve("m1").toAbsolutePath().normalize(),
                oldSnapshot.worldDirectoryPath());
        assertTrue(oldSnapshot.tags().contains("tag-a"));
    }

    @Test
    void registeredIdsOfReturnsProviderOwnIds() {
        MapRegistry registry = new MapRegistry();
        Object provider = new Object();
        MapTestKit.registered(registry, provider, tempDir, "m1", Set.of());
        MapTestKit.registered(registry, new Object(), tempDir, "m2", Set.of());

        assertEquals(Set.of("m1"), registry.registeredIdsOf(provider),
                "按提供方身份键过滤(7.2 要点 4 同款)");
    }

    /** 模板根注入构造口已随去模板根删除(★收敛守卫:单参构造缺席;无参构造 = 唯一构造口)。 */
    @Test
    void rootInjectingConstructorIsGoneWithPathConvention() throws Exception {
        assertThrows(NoSuchMethodException.class,
                () -> MapRegistry.class.getDeclaredConstructor(Path.class),
                "模板根注入构造必须已删除——注册校验直接验声明路径(★v1.8)");
        assertEquals(1, MapRegistry.class.getDeclaredConstructors().length,
                "构造口唯一(无参);其余注入口一律缺席");
    }
}
