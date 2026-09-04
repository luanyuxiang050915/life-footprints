pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 国内备用镜像（Maven Central 偶发不可达时兜底）
        maven("https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "Footprints"
include(":app")
