// ═══════════════════════════════════════════════════════════════
// 依赖坐标一次定版(设计案 12-代码骨架 §12;后续阶段不再动构建)
// 版本选型出处(2026-10-24 本会话经 plugins.gradle.org / maven-metadata 复核):
//   org.jetbrains.kotlin.jvm 2.4.21 = 2.4.x 最新正式版(portal latest 2.5.0-Beta2 为预发布,不追)
//   com.gradleup.shadow 9.6.1 = portal release(2026-07-22,施工计划时点最新)
//   xyz.jpenilla.run-paper 3.1.0 = 设计案 §12 钉定 3.0.2,但 3.0.2 与 Gradle 9.8 不兼容
//     (本会话实测 2026-10-24:task ':runServer' Missing annotation ——
//      RunServer.downloadPlugins.elements 缺输入/输出注解,Gradle 9 任务验证按错误处理,构建失败)
//     → 按施工指令回退保留 3.1.0,记偏差
// ═══════════════════════════════════════════════════════════════
plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("xyz.jpenilla.run-paper") version "3.1.0"
    id("com.gradleup.shadow") version "9.6.1"
    id("maven-publish")           // 7.2:发布 java component 到 mavenLocal——第三方 examples 工程
                                  // compileOnly com.hydrokarbs:Hydrokarbs:3.0.0 的编译期来源(§13.4);
                                  // 运行期零新增依赖(§7.2「别的插件不改我们的 jar 就能加模式」)
}

group = "com.hydrokarbs"
version = "3.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 服务端运行期自己提供 → 一律 compileOnly
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testCompileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // 1.3:测试假实现 implements PlatformPort(签名引用 org.bukkit.plugin.Plugin),
    //   JUnit discovery 反射展开测试嵌套类时需加载该类型——testCompileOnly 不进 test
    //   runtime,会 NoClassDefFoundError。补 testRuntimeOnly(同坐标;不降级 compileOnly、
    //   不影响产物依赖;测试仍零 Bukkit import、不碰 Bukkit 静态)。
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")  // 冻结分层/聚合纯度(1.4 用)
    // 1.2:测试运行期需要 Adventure 实现(MatchMode#build 兜底调 Component.text);
    // 版本 = 4.26.1,对齐 gradle 缓存中 paper-api 1.21.11 POM 的 adventure-bom。
    // 纯 Java 库(无 Bukkit 依赖),不违反离线测试纪律;不把 paper-api 降为 testImplementation。
    testImplementation("net.kyori:adventure-api:4.26.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// 7.2:mavenLocal 发布(java component = 普通 jar,非 shadowJar——第三方 compileOnly
// 只需编译期可见 API 面;shadow 产物不进发布,禁 shade 纪律 14.35 的供给侧配合)
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    // shadow 打包链路:mergeServiceFiles 保住 META-INF/services(§14.13)
    shadowJar {
        archiveClassifier.set("all")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }

    // run-paper 自动把 shadowJar 塞进测试服;不另写 Copy 任务避免双 jar 同名冲突
    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    // plugin.yml 的 ${version} 插值展开
    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
