<p align="center">
  <img src="docs/icon/app-icon.png" alt="煲机助手 · Burn-in Tool 应用图标" width="144">
</p>

<h1 align="center">煲机助手 · Burn-in Tool</h1>

<p align="center">
  安卓耳机煲机（burn-in）工具 · 用科学的声音信号让新耳机的振膜更快进入稳定状态 · 除检查更新外全程离线、无账号、免费开源
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg" alt="要求 Android 8.0 及以上">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF.svg" alt="Kotlin + Jetpack Compose">
</p>

<p align="center"><b>简体中文</b> · <a href="README_EN.md">English</a></p>

---

## 功能

### 双路线煲机

- **方案煲机**：标准四阶段 · 120 小时（白噪舒缓 → 粉噪适应 → 粉噪稳定恒定 → 白噪/粉噪每 30 分钟轮换），或自定义四阶段（总时长 24–240 小时，按 10/10/60/20 比例自动分配）。四阶段支持拖拽调整播放顺序，各阶段响度可调（默认比例不变），稳定阶段可用单曲/歌单替换粉噪恒定；配置作用于标准与自定义方案并持久记忆，阶段响度经系统媒体音量表达。
- **自由煲机**：音源任选，时长提供 2/8/16/24/48/72 小时预设，或自定义 1–999 小时。
- 支持开始/暂停/继续/结束；未完成的方案会话保留检查点，可从上次进度续播。

### 音源

- 7 种合成音源：白噪音、粉红噪音、300Hz 正弦波、150Hz 方波、低频扫频（100–200Hz）、宽频扫频（100Hz–10kHz）、混合煲机（白噪+粉噪）。
- 自定义本地音乐：通过系统文件选择器导入音频文件，循环播放，可随时移除。

### 进度记录

- 累计煲机时长与会话次数小结。
- 历史记录分页列表（每页 20 条，滚动自动加载），展示每次会话的方案、计划时长与实际进度。
- 一键清除全部记录（二次确认，不可恢复）。

### 后台前台播放

- 前台服务保活，通知常驻显示剩余时间倒计时，支持通知栏暂停/继续/结束。
- 音频焦点管理：来电等短暂打断后自动恢复，拔出耳机立即暂停。
- 进度每 60 秒落库，退出应用后重新打开可恢复到进行中界面；到达计划时长发出「煲机完成」系统通知。

### 屏幕与外观

- **屏幕常亮**：播放界面保持亮屏。
- **不息屏模式**：播放中保持亮屏，超时后自动降至最低亮度，避免部分机型息屏中断后台播放。
- 深浅色主题：跟随系统 / 强制浅色 / 强制深色。
- Android 12+ 动态取色，另有 6 套预置调色盘可选。
- 简体中文 / 英文双语：默认跟随系统语言自动检测，可在设置中手动切换，切换即时生效。

### 应用内更新

- **更新来源**：检查本仓库的 GitHub Release（匿名访问公开页面，不需要账号、token 或任何配置），按设备 ABI 匹配资产（优先 `arm64-v8a`，其次 `armeabi-v7a`），只下载 release 包。
- **自动检查**：应用启动时静默检查一次，发现新版本弹窗询问「稍后 / 下载并安装」；已是最新或检查失败时静默不打扰。
- **手动检查**：设置 → 关于 → 「检查更新」；检查结果一律以弹窗展示、点确认关闭——「当前已是最新版本」「发现新版本但本机无适配安装包」「检查失败」；发现可用新版本则进入下载安装弹窗。
- **下载与安装**：下载进度可视（进度条、实时网速、已下载/总大小），完成后交给系统安装器，由用户确认安装，不静默安装。
- **稍后三档**：本次（仅当前进程）/ 7 天 / 下个版本，仅作用于自动提示，手动检查不受影响。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- Room（进度持久化）+ DataStore（偏好设置）
- 前台服务 + 通知媒体控制
- 最低支持 Android 8.0（minSdk 26），target/compileSdk 36，JVM 目标 17

## 构建方法

环境要求：JDK 17、Android SDK（API 36）。

1. **JDK 17**：默认读取 `JAVA_HOME` 环境变量；也可以在项目根目录的 `gradle.properties` 中指定：
   ```properties
   org.gradle.java.home=path/to/jdk-17
   ```
2. **Android SDK**：项目根目录的 `local.properties`（已被 gitignore）中写入：
   ```properties
   sdk.dir=path/to/android-sdk
   ```
   用 Android Studio 打开项目会自动生成该文件。
3. 构建：
   ```bash
   ./gradlew assembleDebug        # Debug 包（armeabi-v7a / arm64-v8a 各一）
   ./gradlew assembleRelease      # Release 包（minify + 资源收缩，按 ABI 分包）
   ./gradlew :app:testDebugUnitTest  # 单元测试
   ```
4. **Release 签名（可选）**：在项目根目录放置 `key.properties` 与签名文件：
   ```properties
   storePassword=...
   keyPassword=...
   keyAlias=...
   storeFile=path/to/keystore.jks
   ```
   没有 `key.properties` 时构建不会失败，Release 以未签名形式产出。

> 仓库在 `settings.gradle.kts` 中前置了阿里云 Maven 镜像以缓解国内网络抖动，镜像不可达时会自动回退到 google / mavenCentral 官方源。

## 打包产物

APK 按 ABI 分包（`armeabi-v7a` / `arm64-v8a`，无 universal 包），产物统一命名为：

```
open-burnin-tool-v<版本号>-<abi>-<debug|release>.apk
```

例如 `open-burnin-tool-v1.5.0-arm64-v8a-release.apk`、`open-burnin-tool-v1.5.0-armeabi-v7a-debug.apk`。

发布为**手动流程**，无自动发布 workflow：

1. 递增 `app/build.gradle.kts` 顶部的 `appVersionName`，并递增 `defaultConfig` 的 `versionCode`；
2. `./gradlew assembleRelease`（产物在 `app/build/outputs/apk/release/`）；
3. 在 GitHub 创建与 `appVersionName` 同名的 `v<版本号>` Release，上传两个 ABI 的 release APK 作为资产。

应用内更新即从这些 Release 资产按 ABI 匹配下载，因此发布时须遵守：**标签/Release 版本号与 `appVersionName` 一致**（应用按资产名中的 `-v<版本号>-` 匹配，不一致会匹配不到、被误报为「未找到适用于当前设备架构的安装包」）、**资产名保持 `open-burnin-tool-v<版本号>-<abi>-release.apk` 不变**（匹配还依赖其中的 `-release` 与 ABI 片段）、**递增 `versionCode`**（否则系统安装器拒绝覆盖安装）、**使用与应用已装版本相同的签名密钥**构建（否则安装器报签名冲突）。现有 CI `.github/workflows/build-apk.yml`（push 到 main 触发）只上传 Actions Artifact，不创建 Release。

## 目录结构

```
app/src/main/java/com/github/gbandszxc/obt/
├── data/       # Room 持久化、仓库与设置
├── domain/     # 煲机方案与进度逻辑
├── playback/   # 前台服务、合成音源播放器、播放控制
├── update/     # 应用内更新（检查 / 下载 / 安装）
└── ui/         # Compose 界面（煲机 / 记录 / 设置 / 主题；更新弹窗在 ui/update/）
docs/           # 产品定义（PRODUCT.md）与设计系统（DESIGN.md）
```

## License

[MIT](LICENSE)
