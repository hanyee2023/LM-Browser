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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2") // 节点列表

    // 不再依赖 libv2ray。xray-core 二进制由 GitHub Actions 在编译时下载并打包到 assets/xray/xray。
}

// 把 xray-core 二进制自动下载进 assets，本地 assembleDebug 与 CI 行为一致。
val downloadXray by tasks.registering {
    val assetsDir = layout.projectDirectory.dir("src/main/assets/xray")
    val xrayBin = assetsDir.file("xray")
    // 仅当二进制不存在时才下载，避免重复拉取
    onlyIf { !xrayBin.asFile.exists() }
    doLast {
        assetsDir.asFile.mkdirs()
        val ver = "v1.8.4"
        exec {
            commandLine(
                "bash", "-c",
                "set -e; " +
                "curl -L -o /tmp/xray.zip https://github.com/XTLS/Xray-core/releases/download/$ver/Xray-android-arm64-v8a.zip; " +
                "unzip -o /tmp/xray.zip -d /tmp/xray-extract; " +
                "cp /tmp/xray-extract/xray ${xrayBin.asFile.absolutePath}; " +
                "chmod +x ${xrayBin.asFile.absolutePath}; " +
                "rm -rf /tmp/xray.zip /tmp/xray-extract"
            )
        }
        println("xray binary ready at ${xrayBin.asFile.absolutePath}")
    }
}

tasks.named("preBuild") { dependsOn(downloadXray) }