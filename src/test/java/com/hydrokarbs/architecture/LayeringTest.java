package com.hydrokarbs.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
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
@AnalyzeClasses(packages = "com.hydrokarbs", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    // 分析范围注(7.1 施工,记 modifiedFiles+decisions):importOptions = DoNotIncludeTests——
    // 冻结命题均为产物(main 源集)分层契约,api 测试类住 com.hydrokarbs.api 包、自带
    // junit/testkit 依赖,此前无 api 包故未暴露;7.1 起不排除即假阳性红旗。对 noClasses 型
    // 规则,从分析集移除测试类只会减少候选违规,不可能把已绿规则变红——命题不变、非放宽
    // (施工计划 §3 纪律 4:修测试单独说明)。

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

    /**
     * ★-末条:api 只依赖 engine 与平台原语(§16.2 原样;7.1 起 api 包有实体)。
     *
     * <p><b>允许集三项技术修正(7.1 施工,命题不变、非放宽断言;施工计划 §3 纪律 4
     * 的「修测试单独说明」在册)</b>:
     * ① {@code net.kyori..}——Adventure 是 Paper 平台文本 API,引擎签名已在用
     * Component({@code MatchMode#displayName}/{@code MatchModeInfoPort});
     * {@code MiniGameApiImpl} 的 displayName/description MiniMessage 序列化属同源
     * 平台原语(07 §7.1 步骤 3 转换表);
     * ② {@code com.hydrokarbs.map..}——07 §7.2 白名单明列「map/ 读面」;
     * {@code maps()} 快照的转换源是 {@code MapRegistry}/{@code MapDefinition}
     * (registerMap(Plugin, MapDefinition) 签名必需,7.5 增量同源);
     * ③ {@code com.hydrokarbs.api..}——api 自身组合面:接口↔view record 同包依赖,
     * 冻结面的组成部分(非引擎外部件,放行不构成任何越界通道)。
     * 命题不变:api 仍不得依赖 mode/**、component/**、bridge/**、service/**、
     * 任何 Impl(测试类已经类级 DoNotIncludeTests 移出分析)。
     */
    @ArchTest
    static final ArchRule apiDependsOnlyOnEngine =
            classes().that().resideInAPackage("..api..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("..engine..", "java..", "io.papermc..", "org.bukkit..",
                            "net.kyori..", "com.hydrokarbs.map..", "com.hydrokarbs.api..")
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

    /**
     * ★40-增款(★v1.1;14-陷阱清单 14.37/19-D10;7.5 施工文档步骤 11):服务层不得
     * import {@code api/**}——服务是引擎内部居民,不消费 {@code MiniGameApi}(三通道
     * 原文保留;{@code joinSession}/leaveSession 扩大的是外部调用方阵营,不构成第四通道)。
     * 显式两包锚定(engine.service 与顶层 service;沿 1.4 锚定口径),与既有 ★40-a/b
     * 并列、无一字放宽。
     */
    @ArchTest
    static final ArchRule servicesMustNotImportApi =
            noClasses().that().resideInAnyPackage("com.hydrokarbs.engine.service..",
                            "com.hydrokarbs.service..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.hydrokarbs.api..");

    /** ★40-b:组件与模式不得 import 服务层(engine.service 与顶层 service)。 */
    @ArchTest
    static final ArchRule modesAndComponentsMustNotImportServices =
            noClasses().that().resideInAnyPackage("com.hydrokarbs.component..", "com.hydrokarbs.mode..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.hydrokarbs.engine.service..", "com.hydrokarbs.service..");

    // ── ★v4 补充条款(2.6 落地;依据 16 §16.2 v4 修订注与 1.4 的 v4 增补注)──
    //   判定补充而非放宽:既有规则(engine 不认识 component/mode、服务双向互不可见)
    //   一字未动;本组只把 v4 新增的会话级组件包 component/session/** 归入 L3、
    //   并显式冻结其消费方白名单(模式可 import 它 = 软接入;引擎/桥接/服务/监听器
    //   一律不可)。MatchModeInstancePort#session() 引用的 SessionView 住 engine.port,
    //   属引擎内部依赖,不触任何既有禁令——「允许」由本注释与 16 §16.2 修订注共同落档。

    /**
     * ★v4:会话级组件包(component/session/**)归 L3——只允许被自身、L4 模式
     * (模式接入会话能力的软接入)与 L0 装配(根包 HydrokarbsAssembly,装配来源唯一)
     * 依赖;引擎(含会话组件注册表)、桥接、服务、监听器、命令层一律不得认识它。
     */
    @ArchTest
    static final ArchRule sessionComponentsAreL3ConsumableOnlyByModesAndAssembly =
            classes().that().resideInAPackage("com.hydrokarbs.component.session..")
                    .should().onlyHaveDependentClassesThat()
                    .resideInAnyPackage("com.hydrokarbs.component.session..", "com.hydrokarbs.mode..",
                            "com.hydrokarbs");
}
