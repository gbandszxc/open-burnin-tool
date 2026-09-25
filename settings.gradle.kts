// 阿里云镜像仅用于本地缓解 maven central / google 的偶发 TLS 抖动；
// GitHub Actions 的海外 runner 访问阿里云会拿到 HTTP 502 并直接中断依赖解析，须直连官方源。
// pluginManagement 块先于脚本主体执行，环境判断需在块内各自声明
pluginManagement {
    val useAliyunMirror = System.getenv("GITHUB_ACTIONS") != "true"
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val useAliyunMirror = System.getenv("GITHUB_ACTIONS") != "true"
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
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
