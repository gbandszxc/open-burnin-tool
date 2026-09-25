import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

// 版本号单一来源：defaultConfig 与产物文件名共用（见文件末尾 androidComponents），避免两处硬编码
val appVersionName = "1.5.0"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
// key.properties 不存在时（如 CI 或新机器）不配置 release 签名，构建不崩
val hasReleaseSigning = keystorePropertiesFile.exists()
if (hasReleaseSigning) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.github.gbandszxc.obt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.gbandszxc.obt"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = appVersionName
    }

    // 按 ABI 分包（v7a / arm64-v8a），不产 universal 包：音频合成纯 Kotlin、无 native 库，
    // 分包只为发布渠道按机型分发最小包；产物命名见文件末尾 androidComponents。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String?
                keyPassword = keystoreProperties["keyPassword"] as String?
                storeFile = (keystoreProperties["storeFile"] as String?)?.let { file(it) }
                storePassword = keystoreProperties["storePassword"] as String?
                // v2 覆盖 API 24+，v3 额外带回密钥轮换能力（旧设备自动忽略 v3 块）
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 显式声明发布包的加固项，避免被隐式默认值反超
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
        }
    }

    // 只保留中英两种语言：应用自身文案为中文，其余 80 余种 locales 全部来自 AndroidX，
    // 过滤后可直接缩小 resources.arsc 并省掉库翻译表。默认资源（values/）始终保留。
    // 注意必须连地区码一起列（zh-rCN 等），只写 "zh" 匹配不到 AndroidX 的 zh-rCN 资源。
    androidResources {
        localeFilters += setOf("en", "zh", "zh-rCN", "zh-rTW", "zh-rHK")
        // 不生成 localeConfig，避免自带语言切换入口与多余资源
        generateLocaleConfig = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // 只清运行期无用的构建元数据；META-INF 下的 LICENSE.txt 属于 Apache-2.0
            // 要求随分发提供的许可证副本，不做剔除。
            // 注：app-metadata.properties / version-control-info.textproto 由 AGP 在打包末段
            // 直接注入，AGP 9 的 dependenciesInfo 与这里的 excludes 都拦不住，各 0.1KB 忽略。
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        // 不开 abortOnError：PropertyEscape 规则会对 gradle.properties / local.properties 里
        // 未转义的 Windows 盘符路径报 error（如 D:/Develop/...），而 local.properties 是
        // 机器本地文件（gitignore），硬卡等于让所有 Windows 检出都无法构建。改为保留报告。
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
        // 依赖与插件版本升级是人工决策，不混进 lint 噪声
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 暂停/停止/常亮等图标超出 icons-core 基础集，扩展图标库由上方 BOM 统一管理
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // 主题等应用设置持久化（Preferences DataStore）
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 产物命名规范：open-burnin-tool-v<versionName>-<abi>-<variant>.apk
// （如 open-burnin-tool-v1.5.0-arm64-v8a-release.apk）；abi 取 splits 生成的 ABI filter 标识，
// variant 即 release/debug；版本号引用 appVersionName 单一来源。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull()?.identifier
            output.outputFileName.set("open-burnin-tool-v$appVersionName-$abi-${variant.name}.apk")
        }
    }
}
