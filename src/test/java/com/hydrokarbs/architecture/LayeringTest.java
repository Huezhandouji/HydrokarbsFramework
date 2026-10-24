package com.hydrokarbs.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 分层冻结(16-路线图与验收 §16.2 代码块;★1/★2/★3 先建/★23;施工计划 1.4)。
 *
 * <p><b>与 §16.2 字面写法的三处不同(均为检验方式的技术修正,命题不变、断言不放宽):</b>
 *
 * <p>① <b>包锚定修正</b>(施工计划 1.4 已裁决):§16.2 口语化的 {@code ..mode..}/{@code ..component..}
 * 会被 ArchUnit 的 {@code ..}(匹配零个或多个包段)把引擎自身的
 * {@code engine.mode}/{@code engine.component} 子包卷进禁止集——聚合根依赖自己嵌套的
 * 组件基类即假阳性红灯。锚定到 L3/L4 顶层包 {@code com.hydrokarbs.mode..}/{@code com.hydrokarbs.component..}
 * 后命题不变(L2 引擎不认识 L3 组件与 L4 模式)。
 *
 * <p>② <b>模式互斥用切片而非逐类两两比较</b>:逐类写法会命中同包自依赖,
 * {@code slices().matching("com.hydrokarbs.mode.(*)..").should().notDependOnEachOther()}
 * 才是「模式 A 不 import 模式 B(含大厅模式)」的准确表达。
 *
 * <p>③ <b>空集条款 {@code allowEmptyShould(true)}</b>(mode 切片与 api 包):框架基线尚无
 * 模式包与 api 包(首模式归 P4、api 面 P7.6 才落盘),ArchUnit 对「无可检查对象」的规则
 * 默认按红处理(防包模式拼错后静默通过)。此处显式声明空集合法——空集上不存在可违反的
 * 依赖,命题空洞成立;第一个模式包/第一个 api 类出现后规则即有牙齿,并非放宽断言。
 *
 * <p><b>SHDF 扩展线条款(★3/★23)规则先建、框架基线空转</b>:宿主桥接包
 * {@code com.hydrokarbs.bridge.role..} 与宿主插件包坐标在框架基线不存在(宿主扩展随
 * Shdf 具体玩法设计案交付),两规则当前通过;价值在于宿主侧接入后第一行越界 import
 * 即红,而非事后补网。宿主插件包坐标以合法包名 {@code com.shdf.roleplugin..} 占位
 * (文档的尖括号占位符非法,见规则处注释),接入时由宿主工程注入实际坐标。
 */
@AnalyzeClasses(packages = "com.hydrokarbs")
class LayeringTest {

    /** ★1:引擎不认识任何模式(锚定修正后的禁入集 = L3/L4 顶层包)。 */
    @ArchTest
    static final ArchRule engineMustNotKnowModes =
            noClasses().that().resideInAPackage("com.hydrokarbs.engine..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.hydrokarbs.mode..", "com.hydrokarbs.component..");

    /** ★2:模式互不依赖(切片表达;框架基线无模式包,空切片显式合法)。 */
    @ArchTest
    static final ArchRule modesMustNotKnowEachOther =
            slices().matching("com.hydrokarbs.mode.(*)..")
                    .should().notDependOnEachOther()
                    .allowEmptyShould(true);

    // ↓ SHDF 扩展线(宿主角色桥接,实现随 Shdf 具体玩法设计案 08 维护):
    //   框架泛化条款「宿主桥接仅被其包装组件 import」;宿主插件包坐标由宿主工程注入,
    //   框架基线不含宿主桥接包——宿主扩展未接入时条款空转(规则先建,§16.2 映射表)。
    //   占位坐标:文档的 <宿主角色插件包>.. 含尖括号,ArchUnit 1.3.0 的 PackageMatcher
    //   构造即抛 IllegalArgumentException(实测),故以合法包名占位——按 v1.1-D13
    //   「宿主另立 com.shdf.*」取 com.shdf.roleplugin..;接入时替换为真实坐标。
    /** ★3:仅宿主桥接扩展包引用宿主角色插件。 */
    @ArchTest
    static final ArchRule onlyBridgeImportsRolePlugin =
            noClasses().that().resideOutsideOfPackage("com.hydrokarbs.bridge.role..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.shdf.roleplugin..");

    /** ★23:仅宿主包装组件可触桥接——模式与其他组件永远拿不到桥接门面(§16.2 原样的双排除写法)。 */
    @ArchTest
    static final ArchRule onlyWrapperComponentTouchesBridge =
            noClasses().that().resideOutsideOfPackage("com.hydrokarbs.bridge.role..")
                    .and().resideOutsideOfPackage("com.hydrokarbs.component.role..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.hydrokarbs.bridge.role..");

    /** ★-末条:api 只依赖 engine 与平台原语(§16.2 原样;框架基线无 api 包,空集显式合法)。 */
    @ArchTest
    static final ArchRule apiDependsOnlyOnEngine =
            classes().that().resideInAPackage("..api..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("..engine..", "java..", "io.papermc..", "org.bukkit..")
                    .allowEmptyShould(true);

    // ── ★40 服务层分层冻结(1.6 落地;18 号修正案 §7;14.33 双向互不可见)──
    //   两款均为「规则先建」:engine.service(机制)与顶层 service(参照实现)两个服务包
    //   都不得反向认识模式/组件/宿主桥接;组件与模式则不得 import 任何服务类型。
    //   包匹配沿用 1.4 锚定口径(顶层包锚定,engine.service 由 ★1 引擎条款另行覆盖)。

    /** ★40-a:服务层(engine.service 与顶层 service)不得依赖模式/组件/宿主桥接。 */
    @ArchTest
    static final ArchRule servicesMustNotKnowModesComponentsOrBridge =
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.hydrokarbs.mode..", "com.hydrokarbs.component..",
                            "..bridge.role..");

    /** ★40-b:组件与模式不得 import 服务层(engine.service 与顶层 service)。 */
    @ArchTest
    static final ArchRule modesAndComponentsMustNotImportServices =
            noClasses().that().resideInAnyPackage("com.hydrokarbs.component..", "com.hydrokarbs.mode..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.hydrokarbs.engine.service..", "com.hydrokarbs.service..");
}
