package com.hydrokarbs.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydrokarbs.map.MapDefinition;
import com.hydrokarbs.map.MapRegistry;

/**
 * 地图注册造数器(★单注册通道收敛后的 testkit 共享 fixture,2026-10-27):替代原
 * {@code MapRegistry#replace} 文件式装载的造数用途——内部为地图落盘临时模板目录
 * ({@code <root>/<mapId>/},14.39「先解包后注册」的离线代偿)+ 以假 provider
 * (身份键)调 {@link MapRegistry#register},与 api 注册通道同一入口。
 *
 * <p>临时目录自理:{@link #newTemplatesRoot()} 建系统临时目录下唯一根,进程退出时
 * 统一递归清理;能用 JUnit {@code @TempDir} 的调用点优先传 {@code @TempDir} 根
 * (框架自身的 per-test 清理)。离线:零 Bukkit import,provider 身份键用普通对象
 * (身份比较,7.2 要点 4 同款;生产 = org.bukkit.plugin.Plugin)。
 */
public final class MapTestKit {

    /** 本类造出的全部临时模板根(进程退出时统一递归清理)。 */
    private static final List<Path> ROOTS = new ArrayList<>();

    /** 便捷版假 provider(身份键;身份比较语义,toString 可读便于诊断)。 */
    private static final Object PROVIDER = new Object() {
        @Override
        public String toString() {
            return "map-testkit-provider";
        }
    };

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Path root : ROOTS) {
                deleteRecursively(root);
            }
        }, "map-testkit-temp-cleanup"));
    }

    private MapTestKit() {
    }

    /**
     * 自理临时模板根(系统临时目录下唯一子目录;注册期模板目录校验依据)。
     * 无 JUnit {@code @TempDir} 可用的装配点(SwitchHarness 等自足基座)用本口。
     */
    public static Path newTemplatesRoot() {
        try {
            Path root = Files.createTempDirectory("hydrokarbs-map-templates");
            synchronized (ROOTS) {
                ROOTS.add(root);
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("临时模板根创建失败", e);
        }
    }

    /**
     * 落盘 {@code <templatesRoot>/<map.id()>} 模板目录并以 {@code provider} 身份注册
     * (替代原 {@code replace} 的造数用途;返回注册的同一声明实例)。
     *
     * @param registry      目标目录(MapRegistry 实例必须以同一 templatesRoot 构造)
     * @param templatesRoot 世界模板根(与 registry 构造根一致)
     * @param provider      提供方身份键(测试内身份比较用,任意对象)
     * @param map           建好的地图声明(纯静态值)
     */
    public static MapDefinition registered(MapRegistry registry, Path templatesRoot,
                                           Object provider, MapDefinition map) {
        try {
            Files.createDirectories(templatesRoot.resolve(map.id()));
        } catch (IOException e) {
            throw new UncheckedIOException("模板目录落盘失败: " + templatesRoot.resolve(map.id()), e);
        }
        return registry.register(provider, map);
    }

    /** 全声明版便捷口(身份键 = 本类专用 PROVIDER;模板目录落盘自理)。 */
    public static MapDefinition registered(MapRegistry registry, Path templatesRoot,
                                           MapDefinition map) {
        return registered(registry, templatesRoot, PROVIDER, map);
    }

    /**
     * 最小注册式地图便捷版(world 约定 = {@code world_<id>},镜像 OfflineApi.map;
     * 身份键 = 本类专用 PROVIDER)。模板目录落盘自理。
     */
    public static MapDefinition registered(MapRegistry registry, Path templatesRoot,
                                           String id, Set<String> tags) {
        return registered(registry, templatesRoot, PROVIDER,
                new MapDefinition(id, "world_" + id, tags, Map.of()));
    }

    /** varargs 标签便捷版(Set.of 包装后委托 Set 重载)。 */
    public static MapDefinition registered(MapRegistry registry, Path templatesRoot,
                                           String id, String... tags) {
        return registered(registry, templatesRoot, id, Set.of(tags));
    }

    /** 递归删除(进程退出清理专用;目录树浅,不追求原子性)。 */
    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 退出清理尽力而为:删除失败不阻断其余根的清理
                }
            });
        } catch (IOException ignored) {
            // 同上:退出清理尽力而为
        }
    }
}
