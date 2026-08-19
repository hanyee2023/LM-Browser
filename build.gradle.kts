// 顶层构建脚本。AGP 版本需与 Android Studio / CloudStudio 的 Gradle 版本匹配。
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    ext {
        agp_version = "8.5.2"
        kotlin_version = "1.9.24"
        // libv2ray 由 v2rayNG 维护，用于把 v2ray 核心打进 App。
        // 版本以 v2rayNG release 中发布的 aar 为准，这里用占位版本，请替换为实际发布版。
        libv2ray_version = "5.20.0"
    }
    dependencies {
        classpath("com.android.tools.build:gradle:$agp_version")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
