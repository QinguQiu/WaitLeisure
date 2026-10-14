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

android {
    namespace = "com.waitleisure"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.waitleisure"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = "2.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Release 直接复用 debug 签名。
            //
            // 这是个自用/侧载项目，不上架商店 —— 用正式 keystore 反而带来
            // 「keystore 丢了就再也签不出能覆盖安装的包」的风险。
            // 真要上架时把这里换成自己的 signingConfig 即可。
            // AGP 会在缺失时自动生成 ~/.android/debug.keystore，CI 上同样适用。
            signingConfig = signingConfigs.getByName("debug")

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