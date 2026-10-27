package com.hydrokarbs.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hydrokarbs.testkit.MapTestKit;

/**
 * 地图目录(施工计划 2.2「测试与验收」;§15.2 引用原子替换;★单注册通道收敛):
 * {@code register}/{@code unregister} 语义——注册保序(目录顺序 = 注册序)、
 * 重复 id 响亮抛点名先到者、摘除幂等 no-op;{@code byId}/{@code byTag}/{@code all}
 * 查询;摘除后持有旧快照者不受影响(不可变快照语义);无参构造已随文件式装载
 * 通道删除(反射守卫)。离线:零 Bukkit import,模板目录走 MapTestKit fixture。
 */
class MapRegistryTest {

    @TempDir
    Path tempDir;

    /** 模板根镜像生产行形状:<数据目录>/maps/templates(20-D2 约定)。 */
    private Path templatesRoot() {
        return tempDir.resolve("maps").resolve("templates");
    }

    @Test
    void emptyRegistryQueriesReturnEmpty() {
        MapRegistry registry = new MapRegistry(templatesRoot());
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.byId("nothing").isEmpty());
        assertTrue(registry.byTag("any").isEmpty());
    }

    @Test
    void registerKeepsOrderAndQueriesWork() {
        MapRegistry registry = new MapRegistry(templatesRoot());
        MapTestKit.registered(registry, templatesRoot(), "m1", "pvp", "small");
        MapTestKit.registered(registry, templatesRoot(), "m2", "pvp");
        MapTestKit.registered(registry, templatesRoot(), "m3", "coop");

        assertSame("m1", registry.byId("m1").orElseThrow().id());
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
        MapRegistry registry = new MapRegistry(templatesRoot());
        MapTestKit.registered(registry, templatesRoot(), "m2", "tag");
        MapTestKit.registered(registry, templatesRoot(), "m1", "tag");

        assertEquals(List.of("m2", "m1"), registry.byTag("tag").stream().map(MapDefinition::id).toList(),
                "byTag 过滤保注册序,不重排");
    }

    @Test
    void duplicateIdIsLoudlyRejectedNamingFirstArriver() {
        MapRegistry registry = new MapRegistry(templatesRoot());
        Object first = new Object();
        MapTestKit.registered(registry, templatesRoot(), first,
                new MapDefinition("m1", "world_m1", Set.of("pvp"), Map.of()));

        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> registry.register(new Object(),
                        new MapDefinition("m1", "world_m1", Set.of("coop"), Map.of())),
                "重复 id 响亮抛(20-D3)");
        assertTrue(duplicate.getMessage().contains("m1")
                && duplicate.getMessage().contains("先到者"), "点名先到者: " + duplicate.getMessage());
        assertEquals(1, registry.all().size(), "先到者保留,后来者不入目录");
        assertEquals("pvp", registry.byId("m1").orElseThrow().tags().iterator().next(),
                "目录中的正式条目仍是先到者声明");
    }

    @Test
    void unregisterRemovesAndIsIdempotentNoOp() {
        MapRegistry registry = new MapRegistry(templatesRoot());
        Object provider = new Object();
        MapTestKit.registered(registry, templatesRoot(), provider,
                new MapDefinition("m1", "world_m1", Set.of("pvp"), Map.of()));

        assertTrue(registry.unregister(provider, "m1"), "注册式摘除成功(20-D4)");
        assertTrue(registry.byId("m1").isEmpty(), "摘除后目录移除");
        assertFalse(registry.unregister(provider, "m1"), "已不在目录 = 幂等 no-op");
        assertFalse(registry.unregister(new Object(), "never_registered"),
                "未知 id = 幂等 no-op");
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void oldSnapshotHolderUnaffectedByUnregister() {
        MapRegistry registry = new MapRegistry(templatesRoot());
        MapTestKit.registered(registry, templatesRoot(), "m1", "tag-a");
        MapDefinition oldSnapshot = registry.byId("m1").orElseThrow();

        assertTrue(registry.unregister(new Object(), "m1"));

        // 持有旧快照者不受摘除影响:record 纯静态值,旧引用照常可读
        assertEquals("m1", oldSnapshot.id());
        assertEquals("world_m1", oldSnapshot.world());
        assertTrue(oldSnapshot.tags().contains("tag-a"));
        assertEquals(List.of(), oldSnapshot.spawns("any"));
    }

    @Test
    void registeredIdsOfReturnsProviderOwnIds() {
        MapRegistry registry = new MapRegistry(templatesRoot());
        Object provider = new Object();
        MapTestKit.registered(registry, templatesRoot(), provider,
                new MapDefinition("m1", "world_m1", Set.of(), Map.of()));
        MapTestKit.registered(registry, templatesRoot(), new Object(),
                new MapDefinition("m2", "world_m2", Set.of(), Map.of()));

        assertEquals(Set.of("m1"), registry.registeredIdsOf(provider),
                "按提供方身份键过滤(7.2 要点 4 同款)");
    }

    /** 无参构造已随文件式装载通道删除(★收敛守卫:反射断言零参构造缺席)。 */
    @Test
    void noArgConstructorIsGoneWithFileChannel() {
        assertThrows(NoSuchMethodException.class, () -> MapRegistry.class.getDeclaredConstructor(),
                "无参构造(仅文件装载通道)必须已删除——地图目录只有注册通道");
    }
}
