pluginManagement {
    repositories {
        // ---- 国内镜像优先：dl.google.com 在部分网络下不可达 ----
        // 官方仓库保留在后面兜底。Gradle 按声明顺序尝试，镜像里没有的会自动回落到 google()/mavenCentral()
        // 国内镜像默认启用。GitHub Actions 的 runner 在海外，用环境变量
        // WAITLEISURE_NO_CN_MIRRORS=1 关掉，让 Gradle 直连 google()/mavenCentral()，
        // 避免阿里云在海外超时。Gradle 是按顺序「找不到才换下一个」，
        // 超时不会自动降级，所以这里必须显式切换，不能靠兜底。
        if (System.getenv("WAITLEISURE_NO_CN_MIRRORS") != "1") {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }

        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("WAITLEISURE_NO_CN_MIRRORS") != "1") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }

        google()
        mavenCentral()
    }
}

rootProject.name = "WaitLeisure"
include(":app")