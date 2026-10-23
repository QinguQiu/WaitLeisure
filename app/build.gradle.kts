// 注意：这里必须用 import 而不是写 java.util.Properties ——
// 在 Gradle Kotlin DSL 脚本里，`java` 会被解析成 JavaPluginExtension 那个扩展，
// 而不是 java 包（踩过：报 "Unresolved reference 'load'"）。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 版本号
//
//   versionCode = 仓库提交总数（单调递增，满足 Android 对 versionCode 的要求，自动 +1）
//   versionName = 手写的里程碑号，例如 2.1.0
//
// versionCode 刻意从 git 派生，不用手动改任何文件；取不到 git 时（没装 git / 不是仓库 /
// 从 ZIP 解压得到）退回 1，绝不因此让构建失败。
// 用 providers.exec 而不是 Runtime.exec：前者是配置缓存安全的写法，
// Gradle 会把命令输出纳入配置缓存的指纹，所以提交后版本号一定会跟着变。
// ---------------------------------------------------------------------------
val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// ---------------------------------------------------------------------------
// release 签名：固定一把 keystore，本地和 CI 用**同一把**
//
// 为什么必须固定：GitHub Actions 每次跑都在全新的虚拟机上重新生成
// ~/.android/debug.keystore —— 于是每个 Release 的签名都不一样，
// 用户根本装不上更新（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
// 只能卸载重装，白名单 / 定时 / 任务全部重配。
//
// 密钥从哪来（都不进仓库）：
//   · 本地：local.properties 里的 waitleisure.* 五项（该文件已 gitignore）
//   · CI  ：GitHub Secrets 注入的环境变量 WAITLEISURE_*
//
// 拿不到就退回 debug 签名 —— 保证「clone 下来直接编译」不会因为缺密钥而失败。
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 取值时统一 trim()：
// GitHub Secrets 是从网页输入框粘贴进去的，**很容易带上尾随换行或空格**。
// 一旦口令末尾多个 \n，AGP 打开 keystore 就会报
//   Keystore was tampered with, or password was incorrect
// 而且是在 packaging 阶段才炸 —— 表现成「Build release APK 失败」，
// 完全看不出是空白字符的问题（本地构建又是好的，因为本地读的是 clean 值）。
fun signingSecret(propKey: String, envKey: String): String? =
    (System.getenv(envKey) ?: localProps.getProperty(propKey))
        ?.trim()
        ?.takeIf { it.isNotBlank() }

val releaseStorePassword = signingSecret("waitleisure.storePassword", "WAITLEISURE_STORE_PASSWORD")
val releaseKeyAlias = signingSecret("waitleisure.keyAlias", "WAITLEISURE_KEY_ALIAS")
val releaseKeyPassword = signingSecret("waitleisure.keyPassword", "WAITLEISURE_KEY_PASSWORD")
    ?: releaseStorePassword
val releaseKeyFile = file(
    signingSecret("waitleisure.storeFile", "WAITLEISURE_KEYSTORE")
        ?: "${System.getProperty("user.home")}/.android/waitleisure-release.jks"
)
val hasReleaseKey = releaseKeyFile.exists() && !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.waitleisure"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias ?: "waitleisure"
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.waitleisure"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = "2.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 有正式 keystore 就用它；没有（比如别人 clone 下来）退回 debug 签名，
            // 总比构建失败强。CI 上会显式断言 keystore 已注入，见 release.yml。
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }

            // ⚠️ 这里**曾经**有一段 AGP 9 的 optimization 配置：
            //
            //     optimization {
            //         enable = true
            //         packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            //     }
            //
            // 它让 release 包**启动即崩**：
            //   java.lang.IllegalAccessError: Class kotlin.collections.i
            //   extended by class qi is inaccessible
            //     at androidx.lifecycle.ProcessLifecycleInitializer
            //     at androidx.startup.InitializationProvider.onCreate
            //
            // R8 在包级作用域里把 Kotlin 内部类改了可见性，运行时才炸 ——
            // 编译期一声不吭，debug 包也完全正常（不跑这套优化），
            // 只有 release 包一装到真机上才暴露。v2.1.0 第一版就是这么发出去的。
            //
            // 删掉即可。release 包会大一些，但能跑。
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}