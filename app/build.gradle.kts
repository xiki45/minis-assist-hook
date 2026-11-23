plugins {
    id("com.android.application")
}

android {
    namespace = "com.openminis.hook"
    compileSdk = 35
    // 本机只保留 build-tools 36.0.0（见 minis/AGENT.md）；不显式指定的话
    // AGP 8.7 会去拉默认的 34.0.0 空壳，离线构建立刻失败。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.openminis.hook"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        // 转发线自带 -relay 后缀：和 main 的重定向线共用 applicationId，
        // 版本号必须大于 main，但名字要一眼能区分装的是哪条线。
        versionName = "2.6-relay"
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
}

dependencies {
    // Xposed API, provided by the Xposed framework at runtime. Compile-only.
    compileOnly("de.robv.android.xposed:api:82")
}
