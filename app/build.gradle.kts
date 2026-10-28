plugins {
    id("com.android.application")
}

android {
    namespace = "com.operit.rearprogress"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.operit.rearprogress"
        minSdk = 30
        targetSdk = 36
        versionCode = 147
        versionName = "1.7.13"
        // 右上角显示的提交编号：构建时取当前 HEAD 短 hash（merge 后自动更新）
        val gitHash = try {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(file(".."))
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "nogit" }
        buildConfigField("String", "GIT_COMMIT", "\"$gitHash\"")
    }

    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 模块是纯 Hook 壳：不打包任何依赖进 APK（Xposed API 由宿主提供）
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

// ARM64 proot 环境 AAPT2 修正
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${requested.version}:linux-aarch64")
        }
    }
}

dependencies {
    // 新版 libxposed API 102（LSPosed 2.2.1 宿主提供，compileOnly 不打包）
    compileOnly(files("libs/libxposed-102.jar"))
}

// 纯 Java 模块，不需要 Kotlin 运行时（Kotlin 插件会默认注入，必须剔除以缩小体积）
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains", module = "annotations")
}