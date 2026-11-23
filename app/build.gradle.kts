plugins {
    id("com.android.application")
}

// 两条线共用 applicationId（见 README「两条线」），所以版号从 2.6.1 起划区间：
//   main  = major*10000 + minor*100 + patch     （2.5.0 → 20500）
//   relay = 1_000_000 + 同一公式                 （2.6.1 → 1020601）
// 区间内单调递增、跨线不会撞号：relay 永远压得住 main；反过来 main 装到 relay 上
// 属于降级，要 adb install -r -d 或先卸载。
val major = 2
val minor = 6
val patch = 1
val relayLineOffset = 1_000_000

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
        // 转发线版号 = 区间偏移 + 语义化版本编码，和 main 的重定向线不会撞号。
        versionCode = relayLineOffset + major * 10_000 + minor * 100 + patch
        // 名字带 -relay 后缀，一眼能区分装的是哪条线。
        versionName = "$major.$minor.$patch-relay"
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
