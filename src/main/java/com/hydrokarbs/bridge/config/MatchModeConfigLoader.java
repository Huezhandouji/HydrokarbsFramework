package com.hydrokarbs.bridge.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.hydrokarbs.engine.session.MatchModeConfig;

/**
 * 模式配置装载器:{@code modes/<id>.yml} → {@link MatchModeConfig} 实现
 * (12-代码骨架 §12.1 {@code bridge/config};施工计划 2.2)。
 *
 * <p>容错三铁律的前两条在这里生效(§15.2):
 * <ul>
 *   <li>铁律 1——文件缺失 → 空 config + WARN(容错可用,不炸注册);</li>
 *   <li>铁律 2——值类型非法 → 降级默认 + WARN,不抛不崩
 *       (见 {@link YamlMatchModeConfig} 各取值方法)。</li>
 * </ul>
 *
 * <p>解析异常 → {@link ConfigException} 带文件名(上抛给装载编排层,
 * 按「单文件失败跳过 + SEVERE」处置,铁律 3)。
 *
 * <p>零 Bukkit import;线程契约同 {@link YamlCodec}(解析在虚拟线程/启动期同步均可)。
 */
public final class MatchModeConfigLoader {

    private final YamlCodec codec;
    private final Logger logger;

    public MatchModeConfigLoader(YamlCodec codec) {
        this(codec, Logger.getLogger(MatchModeConfigLoader.class.getName()));
    }

    /** 测试注入日志捕获器的构造口(生产用单参构造)。 */
    MatchModeConfigLoader(YamlCodec codec, Logger logger) {
        this.codec = codec;
        this.logger = logger;
    }

    /**
     * 装载单个模式配置文件。文件不存在 → 空 config(铁律 1,WARN);
     * 文件损坏 → {@link ConfigException} 带文件名。
     */
    public MatchModeConfig load(Path file) {
        String fileName = String.valueOf(file.getFileName());
        if (!Files.isRegularFile(file)) {
            logger.log(Level.WARNING, "模式配置缺失(按全默认装载): {0}", fileName);
            return YamlMatchModeConfig.of(Map.of(), fileName, logger);
        }
        return YamlMatchModeConfig.of(codec.readMode(file), fileName, logger);
    }

    /**
     * Map 背后的 {@link MatchModeConfig} 实现(§15.2 铁律 1/2 的落点)。
     *
     * <p>路径语法:点分段下钻({@code "rounds.best-of"} → 根下 {@code rounds}
     * 节的 {@code best-of} 键);键不存在返回 def;值类型与目标不符时降级
     * def 并 WARN(不抛)。{@code keys} 返回节下子键(文件声明序)。
     */
    public static final class YamlMatchModeConfig implements MatchModeConfig {

        private final Map<String, Object> root;
        private final String fileName;
        private final Logger logger;

        private YamlMatchModeConfig(Map<String, Object> root, String fileName, Logger logger) {
            this.root = root;
            this.fileName = fileName;
            this.logger = logger;
        }

        /** 测试/装载器共用建实例口。 */
        static YamlMatchModeConfig of(Map<String, Object> root, String fileName, Logger logger) {
            return new YamlMatchModeConfig(Map.copyOf(root), fileName, logger);
        }

        @Override
        public int getInt(String path, int def) {
            Object value = resolve(path);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    // 落入下方降级
                }
            }
            if (value != null) {
                warn(path, value, "整数");
            }
            return def;
        }

        @Override
        public String getString(String path, String def) {
            Object value = resolve(path);
            if (value instanceof String text) {
                return text;
            }
            if (value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);              // 标量可无损转字符串
            }
            if (value != null) {
                warn(path, value, "字符串");
            }
            return def;
        }

        @Override
        public List<String> getStringList(String path, List<String> def) {
            Object value = resolve(path);
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    result.add(String.valueOf(item));
                }
                return List.copyOf(result);
            }
            if (value != null && !(value instanceof Map)) {
                return List.of(String.valueOf(value));     // 标量降级为单元素列表(不警告)
            }
            if (value != null) {
                warn(path, value, "字符串列表");
            }
            return def;
        }

        @Override
        public List<String> keys(String section) {
            Object node = resolve(section);
            if (node instanceof Map<?, ?> map) {
                List<String> keys = new ArrayList<>();
                map.keySet().forEach(key -> keys.add(String.valueOf(key)));
                return List.copyOf(keys);
            }
            return List.of();
        }

        /** 点分段下钻;任一段缺席返回 null。 */
        private Object resolve(String path) {
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

        private void warn(String path, Object value, String expect) {
            logger.log(Level.WARNING, "配置键 {0} 期望{1},实为 {2}({3});按默认值装载",
                    new Object[] { path, expect, value.getClass().getSimpleName(), fileName });
        }
    }
}
