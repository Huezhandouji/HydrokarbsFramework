package com.hydrokarbs.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.AttributeBag;
import com.hydrokarbs.engine.port.ResetStrategy;

/**
 * 地图声明与标签读取基准(★v1.8 新形状,26 号修正案):二参构造缺省
 * {@link ResetStrategy#REUSE_DIRTY};标签是词料容器 {@link MapDefinition#TAGS}
 * 一项,经 {@link MapDefinition#tags()} 读取;{@link AttributeBag} 构造即冻结、
 * 无公开写口,{@link AttributeBag#empty()} 为共享空实例。离线:零 Bukkit import。
 */
class MapDefinitionTest {

    @Test
    void twoArgConstructorDefaultsToReuseDirty() {
        MapDefinition declaration = new MapDefinition(Path.of("map_a"), AttributeBag.empty());

        assertEquals(ResetStrategy.REUSE_DIRTY, declaration.resetStrategy(),
                "二参构造缺省 REUSE_DIRTY(未声明即复用)");
        assertTrue(declaration.tags().isEmpty(), "无词料 = 无标签声明(空集)");
    }

    @Test
    void threeArgConstructorKeepsDeclaredStrategyAndReadsTagsKey() {
        AttributeBag metadata = new AttributeBag.Builder()
                .put(MapDefinition.TAGS, Set.of("arena"))
                .build();
        MapDefinition declaration = new MapDefinition(Path.of("map_b"),
                ResetStrategy.TEMPLATE_RESTORE, metadata);

        assertEquals(ResetStrategy.TEMPLATE_RESTORE, declaration.resetStrategy(),
                "三参构造保留声明策略");
        assertEquals(Set.of("arena"), declaration.tags(), "tags() 读 TAGS 词料键");
        assertSame(metadata, declaration.metadata(), "metadata 原样持有词料容器");
    }

    @Test
    void bagHasNoPublicWritePortAfterBuild() {
        Set<String> publicMethods = Arrays.stream(AttributeBag.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("empty", "get", "getOrDefault", "has"), publicMethods,
                "构造即冻结:公开面只有读口(写只经 Builder)");
        assertTrue(Arrays.stream(AttributeBag.class.getDeclaredFields())
                        .noneMatch(field -> Modifier.isPublic(field.getModifiers())),
                "无公开字段写口");
    }

    @Test
    void builtBagIsDecoupledFromSourceMap() {
        Map<AttributeBag.Key<Set<String>>, Set<String>> source = new HashMap<>();
        source.put(MapDefinition.TAGS, Set.of("before"));
        AttributeBag.Builder builder = new AttributeBag.Builder();
        source.forEach(builder::put);
        AttributeBag bag = builder.build();

        source.put(MapDefinition.TAGS, Set.of("after"));
        source.remove(MapDefinition.TAGS);

        assertEquals(Set.of("before"), bag.getOrDefault(MapDefinition.TAGS, Set.of()),
                "build 即拷贝:来源 Map 后续改动不影响容器读值");
        assertEquals(Set.of("before"), new MapDefinition(Path.of("map_c"), bag).tags(),
                "声明读口同样看不到来源 Map 的后续改动");
    }

    @Test
    void emptyIsSharedInstanceAndYieldsEmptyTags() {
        assertSame(AttributeBag.empty(), AttributeBag.empty(),
                "empty() 返回同一共享实例");
        assertSame(AttributeBag.empty(), new AttributeBag.Builder().build(),
                "空 Builder 的 build() 同样收敛到共享实例");
        assertTrue(new MapDefinition(Path.of("map_d"), AttributeBag.empty()).tags().isEmpty(),
                "空词料 → tags() 空集(20-D3:不被任何模式按标签选中)");
    }
}
