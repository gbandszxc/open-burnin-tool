pluginManagement {
    repositories {
        // 阿里云镜像前置，缓解 maven central / google 的偶发 TLS 抖动
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "BurnIn"
include(":app")

// Load project-local overrides (gitignored local.properties)
val localProps = file("local.properties")
if (localProps.exists()) {
    val props = java.util.Properties().apply { load(localProps.inputStream()) }
    // sdk.dir is read by AGP; java.home (and others) we inject as system properties
    props.filter { it.key.toString() != "sdk.dir" }.forEach { (key, value) ->
        System.setProperty(key.toString(), value.toString())
    }
}
