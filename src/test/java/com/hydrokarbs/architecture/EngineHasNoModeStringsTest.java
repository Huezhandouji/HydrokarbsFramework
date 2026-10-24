package com.hydrokarbs.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 引擎/组件无 mode id 字面量(★4/★13;16-路线图与验收 §16.2;14-陷阱清单 §14.12/§14.21)。
 *
 * <p>§14.12(阶段/规则里的 if-else 反弹)的机械化下半身:引擎与组件源码里出现任何
 * 合法 mode id 字面量即红——引擎按 id 查表、不认识任何具体 id,「大厅特判」
 * (§14.21)正是靠 {@code lobby} 入禁词表堵死的:fallback 是配置项,不是代码分支。
 *
 * <p>扫描范围 = main 源集的 {@code engine/} 与 {@code component/};逐文件逐行,
 * 行内出现禁词(大小写敏感)即命中并打印文件+行号。{@code mode/**} 自身、
 * yml 配置、{@code bridge/**} 不在扫描范围——★4 只管引擎与组件。
 */
class EngineHasNoModeStringsTest {

    /**
     * 合法 mode id 全集(★4 检验方式原文:禁词表含全部合法 mode id)。
     * 框架线本阶段即含 {@code lobby}(★13 的框架闸门等价物,施工计划 §4);
     * 各具体玩法模式 id 不住框架基线——新增模式时同步扩充本表。
     */
    private static final String[] FORBIDDEN_MODE_IDS = {"lobby"};

    /** main 源集的两个扫描根(L4 模式包与桥接不扫)。 */
    private static final List<Path> SCAN_ROOTS = List.of(
            Path.of("src/main/java/com/hydrokarbs/engine"),
            Path.of("src/main/java/com/hydrokarbs/component"));

    @Test
    void engineAndComponentSourcesContainNoModeIdLiterals() throws IOException {
        List<String> hits = new ArrayList<>();
        int scannedFiles = 0;
        for (Path root : SCAN_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;   // component/ 自阶段 4 起才有实体组件,缺席合法
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            }
            scannedFiles += files.size();
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    for (String modeId : FORBIDDEN_MODE_IDS) {
                        if (line.contains(modeId)) {
                            hits.add(file + ":" + (i + 1) + ": " + line.trim());
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(scannedFiles > 0,
                "至少 engine/ 必须被扫描到文件(零文件 = 扫描哑火)");
        assertTrue(hits.isEmpty(),
                () -> "engine/** 或 component/** 出现 mode id 字面量(★4/★13;引擎按 id 查表、"
                        + "不认识任何具体模式 id)。命中 " + hits.size() + " 处:\n"
                        + String.join("\n", hits));
    }
}
