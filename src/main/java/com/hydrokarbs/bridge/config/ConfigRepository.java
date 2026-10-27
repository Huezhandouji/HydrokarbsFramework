package com.hydrokarbs.bridge.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.plugin.java.JavaPlugin;

import com.hydrokarbs.engine.port.ConfigView;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.map.MapRegistry;

/**
 * 配置装载门面 + 默认资源释放(15-适配 §15.2 文件分工;施工计划 2.2)。
 *
 * <p>三件事:
 * <ul>
 *   <li>{@link #saveDefaults()}——首次启动释放默认资源(已存在不覆盖,幂等;
 *       本阶段清单仅 {@code config.yml};{@code modes/} 的玩法实例
 *       由玩法线施工阶段追加);</li>
 *   <li>{@link #loadAll(Path)}——扫 {@code modes/*.yml} → 模式配置缓存、
 *       读 {@code config.yml} 主配置树(地图不经配置装载——由外部调用方经
 *       {@link MapRegistry} 注册,单注册通道)。
 *       <b>铁律 3(局部失败)</b>:单文件失败只跳过该文件并记 SEVERE(点名文件),
 *       不中断整体装载;目录缺席/为空 = 空目录合法,不抛;</li>
 *   <li>{@link ConfigView} 实现——主配置的惰性只读面(每次 {@code get} 下钻当前
 *       配置树,reload 后自动读新值;★39)。</li>
 * </ul>
 *
 * <p><b>Bukkit 触点收敛</b>:读插件 jar 内默认资源的唯一位置是嵌套接口
 * {@link DefaultResourceSource#of(JavaPlugin)}(生产装配注入);本类其余部分
 * 零 Bukkit 依赖,离线测试以临时目录 + 自写默认资源验证。
 *
 * <p><b>线程契约(§15.2)</b>:启动期装载文件量小,主线程同步执行合法;
 * 热重载路径必须在虚拟线程解析、回主线程后调用 {@code loadAll} 换引用
 * (本类字段 volatile,引用替换原子)。
 */
public final class ConfigRepository implements ConfigView {

    private static final Logger DEFAULT_LOG = Logger.getLogger(ConfigRepository.class.getName());

    /** 默认资源清单(幂等释放;玩法线施工阶段在此追加玩法实例)。 */
    private static final List<String> DEFAULT_RESOURCES = List.of("config.yml");

    private final Path dataDir;
    private final DefaultResourceSource defaults;
    private final YamlCodec codec;
    private final MatchModeConfigLoader modeLoader;
    private final Logger logger;

    private final MapRegistry maps;   // ★v1.2:构造注入模板根(注册期模板目录校验依据,20-D2)
    private volatile Map<String, Object> mainConfig = Map.of();
    private volatile Map<String, MatchModeConfig> modeConfigs = Map.of();

    public ConfigRepository(Path dataDir, DefaultResourceSource defaults) {
        this(dataDir, defaults, DEFAULT_LOG,
                dataDir.resolve("maps").resolve("templates"));   // 缺省模板根 = §15.2 目录约定
    }

    /**
     * 装配注入模板根构造口(★v1.2/7.5 施工决策 14):地图注册通道的注册期模板目录
     * 校验依据({@code MapRegistry} 构造注入——map 包纯模型包,不 import bridge);
     * 生产装配传入与 {@code WorldPortImpl} 同源的 {@code maps/templates} 根,
     * 「模板解析路径只有一份约定」由此机械保证。
     */
    public ConfigRepository(Path dataDir, DefaultResourceSource defaults, Path templatesRoot) {
        this(dataDir, defaults, DEFAULT_LOG, templatesRoot);
    }

    /** 测试注入日志捕获器的构造口(生产用双参构造)。 */
    ConfigRepository(Path dataDir, DefaultResourceSource defaults, Logger logger) {
        this(dataDir, defaults, logger,
                dataDir.resolve("maps").resolve("templates"));   // 缺省模板根 = §15.2 目录约定
    }

    private ConfigRepository(Path dataDir, DefaultResourceSource defaults, Logger logger,
                             Path templatesRoot) {
        this.dataDir = dataDir;
        this.defaults = defaults;
        this.logger = logger;
        this.codec = new YamlCodec();
        this.modeLoader = new MatchModeConfigLoader(codec, logger);
        this.maps = new MapRegistry(templatesRoot);
    }

    /**
     * 首次启动释放默认资源:<b>已存在的文件永不覆盖</b>(用户改过的配置优先,
     * 装载时序不变式 1)。目标不存在才从默认资源拷贝;默认资源本身缺席则 WARN 跳过。
     */
    public void saveDefaults() throws IOException {
        Files.createDirectories(dataDir);
        for (String name : DEFAULT_RESOURCES) {
            Path target = dataDir.resolve(name);
            if (Files.exists(target)) {
                continue;                                  // 幂等:二次启动不覆盖
            }
            try (InputStream source = defaults.open(name)) {
                if (source == null) {
                    logger.log(Level.WARNING, "默认资源缺席,跳过释放: {0}", name);
                    continue;
                }
                Files.copy(source, target);
            }
        }
    }

    /**
     * 全量装载(启动期一次;/reload 路径的实现在后续阶段,届时只是再次调用本方法)。
     * 单文件失败跳过 + SEVERE 点名文件(铁律 3);空目录/缺目录 = 空目录合法。
     */
    public void loadAll() throws IOException {
        mainConfig = loadMainConfig();
        modeConfigs = loadModeConfigs();
        logger.log(Level.INFO, "配置装载完成:模式配置 {0} 份(地图不经配置装载,由外部经 MapRegistry 注册)",
                new Object[] { modeConfigs.size() });
    }

    /** 地图目录(引用原子替换语义见 {@link MapRegistry};条目全部来自外部注册)。 */
    public MapRegistry maps() {
        return maps;
    }

    /**
     * 取指定模式的配置视图:缓存命中直接返回;未装载的模式返回空 config + WARN
     * (铁律 1:注册期 build 对缺文件的模式以全默认参数进行,不炸注册)。
     */
    public MatchModeConfig modeConfig(String modeId) {
        MatchModeConfig cached = modeConfigs.get(modeId);
        if (cached != null) {
            return cached;
        }
        return modeLoader.load(modesDir().resolve(modeId + ".yml"));
    }

    // ── ConfigView:主配置惰性只读面 ──────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String path, T fallback) {
        Object value = resolve(mainConfig, path);
        if (value == null) {
            return fallback;
        }
        if (fallback instanceof Integer) {
            return value instanceof Number n ? (T) Integer.valueOf(n.intValue()) : fallback;
        }
        if (fallback instanceof Long) {
            return value instanceof Number n ? (T) Long.valueOf(n.longValue()) : fallback;
        }
        if (fallback instanceof Double) {
            return value instanceof Number n ? (T) Double.valueOf(n.doubleValue()) : fallback;
        }
        if (fallback instanceof Boolean) {
            return value instanceof Boolean b ? (T) b : fallback;
        }
        if (fallback instanceof String) {
            return (value instanceof String || value instanceof Number || value instanceof Boolean)
                    ? (T) String.valueOf(value)
                    : fallback;
        }
        if (fallback instanceof List<?>) {
            return value instanceof List<?> list ? (T) List.copyOf(list) : fallback;
        }
        return fallback;
    }

    // ── 内部:装载编排 ───────────────────────────────────────────

    private Map<String, Object> loadMainConfig() {
        Path file = dataDir.resolve("config.yml");
        if (!Files.isRegularFile(file)) {
            logger.log(Level.WARNING, "主配置 config.yml 缺席,全部按内置默认值运行");
            return Map.of();
        }
        try {
            return codec.readMode(file);
        } catch (ConfigException e) {
            logger.log(Level.SEVERE, "主配置损坏,全部按内置默认值运行(铁律 3:不中断装载)", e);
            return Map.of();
        }
    }

    private Map<String, MatchModeConfig> loadModeConfigs() throws IOException {
        Map<String, MatchModeConfig> loaded = new LinkedHashMap<>();
        for (Path file : listYaml(modesDir())) {
            String modeId = stripYamlSuffix(String.valueOf(file.getFileName()));
            try {
                loaded.put(modeId, modeLoader.load(file));
            } catch (ConfigException e) {
                logger.log(Level.SEVERE, "模式配置装载失败,跳过该文件(铁律 3): " + file, e);
            }
        }
        return Map.copyOf(loaded);
    }

    private Path modesDir() {
        return dataDir.resolve("modes");
    }

    private List<Path> listYaml(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> String.valueOf(path.getFileName()).endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    private static String stripYamlSuffix(String fileName) {
        return fileName.endsWith(".yml") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    /** 点分段下钻(与配置视图的路径语法一致)。 */
    private static Object resolve(Map<String, Object> root, String path) {
        if (path == null || path.isBlank()) {
            return root.isEmpty() ? null : root;
        }
        Object node = root;
        for (String segment : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /**
     * 默认资源源:按资源名开输入流(null = 无该默认资源)。
     * 生产实现见 {@link #of(JavaPlugin)}——<b>本类的唯一 Bukkit 触点</b>,
     * 离线测试以临时目录/内存实现注入。
     */
    @FunctionalInterface
    public interface DefaultResourceSource {

        InputStream open(String resourceName) throws IOException;

        /** 从插件 jar 内读默认资源(JavaPlugin#getResource;唯一 Bukkit 依赖点)。 */
        static DefaultResourceSource of(JavaPlugin plugin) {
            return plugin::getResource;
        }
    }
}
