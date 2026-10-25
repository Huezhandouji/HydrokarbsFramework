import java.util.Collections
import java.util.zip.ZipFile

// ═══════════════════════════════════════════════════════════════
// 第三方消费样例工程构建脚本(7.2 步骤 6 → 7.5 步骤 10 升级;13-扩展步骤 §13.4):
//   ① compileOnly 依赖框架 jar(禁 shade,14.35——框架类不得进本插件 jar);
//   ② paper-api 一律 compileOnly(服务端运行期自己提供);
//   ③ 坐标 com.hydrokarbs:Hydrokarbs:3.0.0 = 主工程 publishToMavenLocal 产物
//     (mavenLocal 仓库;★v1.1-D13 包根 com.hydrokarbs 已在册);
//   ④ check 挂禁 shade 断言任务(verifyNoFrameworkClasses)——本插件 jar 内
//     不得出现 com/hydrokarbs/ 前缀条目(14.35:CI 在打包期红,不留到运行期);
//   ⑤ 测试源集(7.5 步骤 10):低版本拒载判定 + 坏 provider 形状用例(★46/★51 的
//     消费方侧载体;拒绝行为本体由框架侧测试承载)。
// ═══════════════════════════════════════════════════════════════
plugins {
    id("java")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.hydrokarbs:Hydrokarbs:3.0.0")
    // 测试源集同纪律(compileOnly 不进 test 编译类路径,需显式补 testCompileOnly;
    // 与主仓 testCompileOnly 先例同款,14.35 禁 shade 纪律在测试侧同样成立)
    testCompileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testCompileOnly("com.hydrokarbs:Hydrokarbs:3.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // 测试运行期需加载框架与平台类型(TheirPlugin extends JavaPlugin、实现 SessionComponentProvider
    // ——JUnit discovery 反射展开测试类时需加载;主仓 testRuntimeOnly 注释同款先例)
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("com.hydrokarbs:Hydrokarbs:3.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test {
        useJUnitPlatform()
    }

    // 14.35 禁 shade 断言:消费方 jar 内不得出现 com/hydrokarbs/ 前缀类——
    // shade 框架类会导致双引擎/ClassCastException/模式注册进错误注册表(14.35)。
    val verifyNoFrameworkClasses by registering {
        dependsOn(jar)
        doLast {
            val jarFile = jar.get().archiveFile.get().asFile
            val offenders = ZipFile(jarFile).use { zip ->
                Collections.list(zip.entries())
                    .map { it.name }
                    .filter { it.startsWith("com/hydrokarbs/") }
            }
            check(offenders.isEmpty()) {
                "框架类被 shade 进消费方 jar(14.35,禁): $offenders"
            }
        }
    }

    named("check") {
        dependsOn(verifyNoFrameworkClasses)
    }
}
