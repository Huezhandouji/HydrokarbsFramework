package com.hydrokarbs.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 命名空间纪律(★26;02-核心概念 §2.7;14-陷阱清单 §14.26)。
 *
 * <p>状态主人唯一:组件只能写自己命名空间(命名空间 = 组件 id)的键;
 * 跨组件共享走状态主人的强类型读口,不开放裸写。两条腿:
 *
 * <ul>
 *   <li><b>行为腿</b>:{@link AttributeBag#set} 的开发期断言(默认开、生产可关)——
 *       自命名空间写通过;写他人命名空间抛且消息点名调用组件 id 与键 namespace;
 *       开关关闭不抛;无组件帧的裸 set 被拒;包内 setUnchecked(框架/会话壳路径)可用。
 *       本测试与 {@link AttributeBag} 同包,即以「包内视角」驱动收口方法。</li>
 *   <li><b>ArchUnit 复核腿</b>(先建):组件包内声明 {@code AttributeBag.Key} 常量的类,
 *       其常量的全部引用者必须与声明类同包——跨包引用在编译面就该被包私有可见性挡死
 *       (§14.26-③)。框架基线 {@code component/} 尚无实体组件,规则空转;
 *       阶段 4 出现第一个组件后本腿开始有牙齿。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.hydrokarbs")
class AttributeNamespaceTest {

    @AfterEach
    void restoreNamespaceCheckSwitch() {
        AttributeBag.setNamespaceCheckEnabled(true);
    }

    // ── 行为腿:fake 组件(离线;svc 传空壳 record,不碰 Bukkit) ──────────

    /** 组件 A:命名空间 "a"。 */
    static final class ComponentA extends MatchModeComponent {
        ComponentA() {
            super("a", new MatchModeInstanceServices(null, null, null));
        }

        void writeOwn(AttributeBag bag) {
            bag.set(AttributeBag.Key.of("a", "count", Integer.class), 1);
        }

        void writeForeign(AttributeBag bag) {
            bag.set(AttributeBag.Key.of("b", "count", Integer.class), 1);
        }
    }

    /** 组件 B:命名空间 "b"。 */
    static final class ComponentB extends MatchModeComponent {
        ComponentB() {
            super("b", new MatchModeInstanceServices(null, null, null));
        }
    }

    private static AttributeBag bagWithBothComponentsBound() {
        AttributeBag bag = new AttributeBag();
        bag.bindComponent("a", ComponentA.class);
        bag.bindComponent("b", ComponentB.class);
        return bag;
    }

    @Test
    void componentWritingOwnNamespacePasses() {
        AttributeBag bag = bagWithBothComponentsBound();
        ComponentA component = new ComponentA();
        assertDoesNotThrow(component::id, "组件构造即持有 id(命名空间的来源)");
        component.writeOwn(bag);
        assertEquals(java.util.Optional.of(1), bag.get(AttributeBag.Key.of("a", "count", Integer.class)),
                "自命名空间写入成功且可读回");
    }

    @Test
    void writingForeignNamespaceThrowsAndNamesCulpritAndNamespace() {
        AttributeBag bag = bagWithBothComponentsBound();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ComponentA().writeForeign(bag),
                "组件 A 写组件 B 的命名空间必须被开发期断言拦截");
        String message = String.valueOf(ex.getMessage());
        assertTrue(message.contains("id=a"), "异常消息必须点名调用组件 id(a),实际:" + message);
        assertTrue(message.contains("namespace=b"), "异常消息必须点名键的 namespace(b),实际:" + message);
    }

    @Test
    void checkCanBeSwitchedOffForProduction() {
        AttributeBag bag = bagWithBothComponentsBound();
        AttributeBag.setNamespaceCheckEnabled(false);
        assertDoesNotThrow(() -> new ComponentA().writeForeign(bag),
                "断言只为开发期执法,生产关掉后不抛");
        assertEquals(java.util.Optional.of(1), bag.get(AttributeBag.Key.of("b", "count", Integer.class)),
                "关闭断言后写入照常生效");
    }

    @Test
    void bareSetWithoutComponentFrameIsRejected() {
        AttributeBag bag = bagWithBothComponentsBound();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bag.set(AttributeBag.Key.of("a", "count", Integer.class), 1),
                "无组件帧的裸 set(测试方法直接调用)必须被拒");
        assertTrue(String.valueOf(ex.getMessage()).contains("setUnchecked"),
                "拒绝消息须指路框架/会话壳的合法通道(setUnchecked),实际:" + ex.getMessage());
    }

    @Test
    void setUncheckedIsUsableFromWithinPackage() {
        AttributeBag bag = new AttributeBag();
        // 包内通道(容器/会话壳写内核命名空间,如 session):与 AttributeBag 同包即可用
        bag.setUnchecked(AttributeBag.Key.of("session", "startedAt", Long.class), 42L);
        assertEquals(java.util.Optional.of(42L), bag.get(AttributeBag.Key.of("session", "startedAt", Long.class)),
                "setUnchecked 写入成功且可读回(框架/会话壳路径)");
    }

    // ── ArchUnit 复核腿(先建;component/ 空则规则空转通过) ────────────────

    /** ★26 复核:component/** 内声明的 AttributeBag.Key 常量,引用者必须与其同包(§14.26-③)。 */
    @ArchTest
    static void attributeKeyConstantsAreOnlyReferencedWithinTheirPackage(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass declaring : classes) {
            if (!declaring.getPackageName().startsWith("com.hydrokarbs.component")) {
                continue;
            }
            for (JavaField keyField : declaring.getFields()) {
                if (!keyField.getRawType().isEquivalentTo(AttributeBag.Key.class)) {
                    continue;
                }
                for (JavaClass referencing : classes) {
                    boolean referencesKey = referencing.getFieldAccessesFromSelf().stream()
                            .anyMatch(access -> access.getTarget().equals(keyField));
                    if (referencesKey && !referencing.getPackageName().equals(declaring.getPackageName())) {
                        violations.add(referencing.getName() + " 跨包引用 " + keyField.getFullName()
                                + "(Key 常量应包私有,共享走状态主人的强类型读口)");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "AttributeBag.Key 常量被跨包引用(★26/§14.26-③——键常量收窄到包私有,"
                        + "跨组件读走状态主人的读口)。违规 " + violations.size() + " 处:\n"
                        + String.join("\n", violations));
    }
}
