package com.hydrokarbs.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 引擎零状态机 + 零内容词汇(★24;16-路线图与验收 §16.2;14-陷阱清单 §14.24)。
 *
 * <p>引擎只认识 {@code EndResult} 这一个结束货币,且它仅携带去向(★v3.1)——
 * 胜负/原因等结局词汇归组件层。v3 的 sealed 事件族正是自己长着 {@code PhaseChanged}/
 * {@code RoleKill} 撞了本禁词表,才整体下放组件包(§12.2、§0.5.3)——本测试就是
 * 防这段历史重演的闸。
 *
 * <p>源码扫描 {@code src/main/java/com/hydrokarbs/engine/} 全部 {@code .java}:
 * 禁词八枚,标识符边界、大小写敏感;逐文件逐行匹配,命中即失败并打印文件+行号。
 * <b>扫描含注释与字符串——这是特性不是缺陷</b>:注释里写英文内容词的诱惑正是
 * 要拦的(引擎注释用中文表述阶段/规则/阵营/计分)。
 *
 * <p>失败时的唯一正确动作是「把词汇赶出引擎」(下放组件或改用中文表述),不是改断言。
 */
class EnginePurityTest {

    /** 引擎源码根(Gradle test 工作目录 = 工程根,相对路径直接可用)。 */
    private static final Path ENGINE_ROOT = Path.of("src/main/java/com/hydrokarbs/engine");

    /** 禁词八枚(§16.2-★24;标识符边界、大小写敏感;含其派生词,如 PhaseNode/Scoreboard)。 */
    private static final Pattern FORBIDDEN =
            Pattern.compile("\\b(Phase|Rule|WinCondition|EndCondition|Role|Team|Score|Objective)\\w*");

    @Test
    void engineSourcesContainNoStateMachineVocabulary() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(ENGINE_ROOT)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(files.isEmpty(),
                "引擎源码目录不得为空(零文件 = 扫描哑火):" + ENGINE_ROOT);

        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                    hits.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertTrue(hits.isEmpty(),
                () -> "engine/** 出现内容词汇(★24;引擎零状态机零内容词汇)。命中 "
                        + hits.size() + " 处——把词汇下放组件或改用中文表述:\n"
                        + String.join("\n", hits));
    }
}
