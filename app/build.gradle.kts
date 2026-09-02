plugins {
    id("com.android.application")
}

android {
    namespace = "com.openminis.hook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openminis.hook"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.2"
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
