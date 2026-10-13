plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 版本号自动从 git 派生 —— 每次 git commit 自动 +1，不需要手动改任何文件
//
//   versionCode = 仓库提交总数（单调递增，满足 Android 对 versionCode 的要求）
//   versionName = 1.0.<提交数>              例如 1.0.8
//
// 刻意不把 commit hash 塞进版本名，理由：
//   1. Release 的 tag（v1.0.8）已经指向确定的提交，hash 是冗余信息；
//   2. 本地改了文件还没提交时，hash 显示的也是「上一次提交」，本身就不准；
//   3. 代价却是「设置 → 应用」里那一行被撑长，而且 `+` 这类字符在 URL 和文件名里
//      会被百分号编码成 %2B，看着像乱码（早期版本正是踩了这个坑）。
//
// 取不到 git 时（没装 git / 不是仓库 / 从 ZIP 解压得到）退回 1，绝不因此让构建失败。
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
    namespace = "com.selflock"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.selflock"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = "2.0.$gitCommitCount"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
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