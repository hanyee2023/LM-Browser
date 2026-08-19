pluginManagement {
    repositories {
        // 官方源优先（GitHub CI 网络通，直接走官方最稳）
        google()
        mavenCentral()
        gradlePluginPortal()
        // 受限网络兜底镜像（放最后，正常情况不触发）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 官方源优先
        google()
        mavenCentral()
        // 受限网络兜底镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "ProxyBrowser"
include(":app")
