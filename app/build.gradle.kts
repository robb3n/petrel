plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号（同 Mu3ic）：versionCode = git 提交计数，同一个提交重建得到同一个 build 号；
// 回火任务的 forge 只改下面这一行 baseVersionName，不再手动改 versionCode。
val baseVersionName = "0.1.1"

// 没有 git 历史（GitHub 自动生成的源码压缩包）或根本没装 git 时返回 null，版本号退成 <base>-src、build 1，不让构建失败
fun gitOutput(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.let { r -> if (r.result.get().exitValue == 0) r.standardOutput.asText.get().trim() else null }
}.getOrNull()

// 2026-10-04 开源前把最初的 12 个提交压成了一个，提交计数从 12 掉回 1；加上这 12，build 号才只增不减（已装的包不会被判成降级）。
val squashedCommits = 12
val gitCount: Int? = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()
val gitBuild: Int = gitCount?.let { it + squashedCommits } ?: 1
// HEAD 上有 tag v<baseVersionName> 且已跟踪文件没有未提交改动 = 稳定版；否则开发版 <base>-dev.<build>。
// 带着改动在打过 tag 的 HEAD 上构建不算稳定版，免得出一个同名的 <base> 包、覆盖侧载存档里的稳定版。未跟踪文件不算改动。
val trackedTreeClean: Boolean = gitCount != null && providers.exec {
    commandLine("git", "diff", "--quiet", "HEAD")
    workingDir = rootProject.projectDir
    isIgnoreExitValue = true
}.result.get().exitValue == 0
val isStableBuild: Boolean = trackedTreeClean &&
    gitOutput("tag", "--points-at", "HEAD").orEmpty().lineSequence().any { it == "v$baseVersionName" }

// 正式 release key（PKCS12）：路径与口令只在构建机 ~/.gradle/gradle.properties 的 petrel.release.*，不进仓库。
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").map { "petrel.release.$it" }
fun releaseSigningProp(key: String): String? = providers.gradleProperty("petrel.release.$key").orNull

android {
    namespace = "com.robb3n.petrel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robb3n.petrel"
        minSdk = 29
        targetSdk = 35
        versionCode = gitBuild
        versionName = when {
            gitCount == null -> "$baseVersionName-src"
            isStableBuild -> baseVersionName
            else -> "$baseVersionName-dev.$gitBuild"
        }
    }

    // 属性缺失时文件末尾的 taskGraph 检查让 release 构建直接失败，绝不退回 debug key。
    signingConfigs {
        create("release") {
            // file() 不展开 ~：写成 ~/… 时先换成用户目录
            releaseSigningProp("storeFile")?.let { storeFile = file(if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it) }
            storePassword = releaseSigningProp("storePassword")
            keyAlias = releaseSigningProp("keyAlias")
            keyPassword = releaseSigningProp("keyPassword")
        }
    }

    buildTypes {
        debug {
            // 与 core/build.sh 的 gomobile -target 对齐：arm64 真机 + x86_64 模拟器
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            // 对外发布的包只给真机：libgojni.so 单个 ABI 约 60 MB，不带模拟器的 x86_64
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // 模型测试不碰 Android 类；万一误碰（Log 等）返回默认值而不是抛 not mocked
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        // libgojni.so 单个 ABI 约 60 MB；压缩进 APK，经 SSH / adb 传得快，安装时系统解压
        jniLibs { useLegacyPackaging = true }
    }
}

// Go 内核层（mihomo + tsnet）经 gomobile 编成 AAR；Go 源码没变时任务 UP-TO-DATE。
val coreAar = layout.projectDirectory.file("libs/ptcore.aar")
// Run even when the AAR is cached, so interrupted updates cannot skip the gate.
val verifyDNSRules by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/cn-rules.sh", "verify")
}
val buildCore by tasks.registering(Exec::class) {
    dependsOn(verifyDNSRules)
    val coreDir = rootProject.layout.projectDirectory.dir("core")
    // 只改 Go 测试不进 AAR，不该触发 gomobile 重编
    inputs.files(fileTree(coreDir) { include("**/*.go", "ptcore/assets/*.mrs", "ptcore/assets/*.json", "go.mod", "go.sum", "build.sh"); exclude("**/*_test.go") })
    outputs.file(coreAar)
    workingDir = coreDir.asFile
    commandLine("bash", "build.sh", coreAar.asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(buildCore) }

// 会打包 / 签名 release 的任务进了任务图，而签名属性缺了 → 直接失败并指出缺哪个（同 Mu3ic）。
// 只认 assemble / bundle / package / sign 开头、Release(Bundle) 结尾的任务；testReleaseUnitTest、lintRelease 不签名，照样能跑。
val releasePackagingTask = Regex("^(assemble|bundle|package|sign)\\w*Release(Bundle)?$")
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.project == project && releasePackagingTask.matches(it.name) }
    if (buildsRelease) {
        val missing = releaseSigningKeys.filter { providers.gradleProperty(it).orNull.isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "release 构建缺少签名属性：${missing.joinToString()}。" +
                    "把它们写进 ~/.gradle/gradle.properties（见 AGENTS.md「## Release」），不会退回 debug key。"
            )
        }
    }
}

dependencies {
    implementation(files(coreAar))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // 单测里用真的 org.json（Android 的是空实现）：ip-api 应答的解析要测
    testImplementation("org.json:json:20240303")
}
