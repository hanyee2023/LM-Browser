pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // libv2ray 经 jitpack 构建
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "ProxyBrowser"
include(":app")
