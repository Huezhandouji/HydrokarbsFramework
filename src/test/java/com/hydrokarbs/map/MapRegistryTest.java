package com.hydrokarbs.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 地图目录(施工计划 2.2「测试与验收」;§15.2 引用原子替换):
 * {@code byId}/{@code byTag}/{@code all} 查询;整表替换后
 * 持有旧快照者不受影响(不可变快照语义)。离线:零 Bukkit import。
 */
class MapRegistryTest {

    private static MapDefinition map(String id, String... tags) {
        return new MapDefinition(id, "world_" + id, Set.of(tags), Map.of());
    }

    @Test
    void emptyRegistryQueriesReturnEmpty() {
        MapRegistry registry = new MapRegistry();
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.byId("nothing").isEmpty());
        assertTrue(registry.byTag("any").isEmpty());
    }

    @Test
    void byIdByTagAndAll() {
        MapRegistry registry = new MapRegistry();
        registry.replace(List.of(map("m1", "pvp", "small"), map("m2", "pvp"), map("m3", "coop")));

        assertSame("m1", registry.byId("m1").orElseThrow().id());
        assertTrue(registry.byId("ghost").isEmpty());

        assertEquals(2, registry.byTag("pvp").size());
        assertEquals(1, registry.byTag("coop").size());
        assertTrue(registry.byTag("nothing").isEmpty());

        assertEquals(3, registry.all().size());
        assertEquals(List.of("m1", "m2", "m3"), registry.all().stream().map(MapDefinition::id).toList());
    }

    @Test
    void replaceSwapsWholeTableAtomicallyForOldSnapshotHolders() {
        MapRegistry registry = new MapRegistry();
        registry.replace(List.of(map("m1", "tag-a")));
        MapDefinition oldSnapshot = registry.byId("m1").orElseThrow();

        // 整表替换:读方要么旧表、要么新表,不会读到半截
        registry.replace(List.of(map("m2", "tag-b")));

        assertTrue(registry.byId("m1").isEmpty(), "替换后旧 id 摘除");
        assertTrue(registry.byId("m2").isPresent());
        assertEquals(1, registry.all().size());

        // 持有旧快照者不受替换影响:record 纯静态值,旧引用照常可读
        assertEquals("m1", oldSnapshot.id());
        assertEquals("world_m1", oldSnapshot.world());
        assertTrue(oldSnapshot.tags().contains("tag-a"));
        assertEquals(List.of(), oldSnapshot.spawns("any"));
    }

    @Test
    void allReturnsUnmodifiableView() {
        MapRegistry registry = new MapRegistry();
        registry.replace(List.of(map("m1")));
        var all = registry.all();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> all.add(map("m2")));
        assertFalse(registry.all().isEmpty());
    }
}
