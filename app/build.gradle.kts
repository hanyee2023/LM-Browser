plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxybrowser.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxybrowser.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0") // ProxyController：仅代理 WebView
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2") // 节点列表

    // xray-core 的 Android 绑定（内嵌代理内核）。
    // 版本/构件以 libv2ray 仓库发布为准；若 jitpack 编译失败，
    // 改用其 GitHub Releases 的预编译 AAR（下载放 app/libs/ 并 implementation(files("libs/...aar"))）。
    implementation("com.github.2dust:libv2ray:1.8.5")
}
