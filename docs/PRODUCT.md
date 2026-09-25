# PRODUCT.md — 产品定义（煲机助手）

> 依据当前 v1.5.0（versionCode 4，minSdk 26 / targetSdk 36）实际实现固化。功能或交互行为变更时须同步更新本文件。

## 产品定位

安卓耳机煲机工具（名称：中文「煲机助手」/ 英文「Burn-in Tool」，英文标识统一为 Burn-in Tool，不再使用 Burn-in Assistant）：用科学的声音信号（噪声/扫频）与用户自定义本地音乐让新耳机振膜快速进入稳定状态。除「检查更新」会匿名访问 GitHub 公开 Release 页面外，煲机功能全程离线、无账号、免费开源，适合拿到新耳机、想按方案或自由节奏煲机的个人用户。

## 核心功能清单

### 双路线煲机（煲机页，`ui/burnin/BurnInIdleContent.kt`）

- **方案煲机**（`BurnMode.PLAN`，`playback/BurnInViewModel.kt`）
  - 标准四阶段 · 120 小时：舒缓 12h 白噪（1/5 音量）→ 适应 12h 粉噪（1/3）→ 稳定 72h 粉噪恒定（7/15）→ 轮换 24h 白噪↔粉噪每 30 分钟轮换（3/5）（`domain/model/BurnPlans.kt`；阶段时长/音量沿原版逆向结论，内置音乐音源已移除，音源为本版合成编排）。
  - 自定义四阶段：总时长 24–240 小时（默认 48，步进 ±12，`BurnInUiState.PLAN_CUSTOM_HOURS_RANGE/_STEP`），按 10/10/60/20 比例缩放到四阶段，轮换阶段轮换周期保持 30 分钟（`BurnPlans.custom`）。
  - 阶段编排：四阶段播放顺序可拖拽调整；各阶段响度可按阶段身份覆盖（1–100，默认仍为 1/5、1/3、7/15、3/5）——行内点百分比弹出响度对话框，±5 步进按钮 + 1–100 手动输入（行内校验，非法禁用确定，可一键恢复默认）；稳定阶段模式（「粉噪恒定」/「本地音乐（单曲或多曲有序歌单）」）与曲目勾选收进「稳定阶段播放内容」弹窗，经稳定阶段行内编辑图标进入，弹窗内变更即时生效。编排作用于标准与自定义两种四阶段方案并持久记忆（DataStore，缺省为顺序 0..3、无响度覆盖、粉噪恒定）（`ui/burnin/StageArrangementSection.kt`；域层 `BurnPlan.withStageOrder`/`BurnPlan.withStageGains`，`BurnPlans.classic`/`BurnPlans.custom` 的 `steadyTrackIds` 稳定阶段注入）。
- **自由煲机**（`BurnMode.FREE`）
  - 音源任选：内置 7 合成音源或已导入的本地音乐（分组下拉，`ui/burnin/SoundSourceDropdown.kt`）。
  - 时长预设 2/8/16/24/48/72 小时（`BurnPlans.QUICK_HOURS`，默认 8h），或自定义 1–999 小时（预设与自定义互斥，`BurnInUiState.FREE_CUSTOM_HOURS_RANGE`）。
- 开始/暂停/继续/结束：播放中配置区整体被进度态替代（`ui/burnin/BurnInTab.kt` ActiveContent）。

### 音源（`domain/model/SoundSource.kt`、`playback/SynthPlayer.kt`）

- 7 种合成音源：正弦波 300Hz、粉红噪音（Paul Kellet 滤波 + 运行峰值归一化）、方波 150Hz、白噪音、低频扫频 100–200Hz（40s 循环）、混合煲机（白噪+粉噪各 50%）、宽频扫频 100Hz–10kHz（74s 循环）。UI 音效目录取 `SoundSource.catalog`（不含本地音源占位项）。
- 自定义本地音乐：系统文件选择器（SAF）导入音频文件，拷贝进应用私有目录并循环播放（`data/TrackRepository.kt`、`playback/BurnInViewModel.importTrack`）；支持移除（删文件 + 删记录，二次确认）。方案模型以 `SoundSource.LOCAL_TRACK`（不入 UI 目录）+ `BurnPhase.localTrackIds` 有序歌单表达（单曲即单元素歌单），播放时 UI 显示曲目名。方案内本地音乐阶段按歌单顺序循环播放：一曲播完自动接下一曲（末尾回环），通知曲名即时更新；失效曲目（被删除/不可读）自动跳到下一条可用曲目，全部失效回退既有兜底音源；编排配置中勾选的曲目被删除时自动修剪歌单，清空后回退粉噪恒定。

### 进度记录（记录页，`ui/history/HistoryTab.kt`）

- 顶部小结：累计煲机 + 会话次数 + 清除入口。
- 分页列表：每页 20 条按开始时间倒序，滚近末尾自动追加，尾项提示「加载中 / 共 N 条」（`ui/history/HistoryViewModel.kt`，`data/BurnInRepository.sessionPage`）。
- 清除全部记录：二次确认弹窗（删记录 + 重置累计统计，不可恢复）。
- 会话行：时间 + 状态（进行中/已暂停/已完成/已结束）、方案与计划时长、实际已煲；自由煲机会话回显所用音效（内置音源本地化名或本地曲目名快照，旧数据无此信息则不显示）。

### 后台前台播放与通知控制（`playback/PlaybackController.kt`、`playback/BurnInService.kt`）

- 前台服务保活，通知常驻：播放中显示剩余时间倒计时（系统 chronometer），动作按钮暂停/继续、结束；点通知回到应用。
- 音频焦点：短暂丢失（来电等）暂停并在焦点回归后自动恢复；永久丢失保持暂停。拔耳机即暂停。
- 响度策略：方案煲机（标准/自定义四阶段）各阶段响度经系统媒体音量（STREAM_MUSIC）按比例映射表达（起播记录原音量、暂停/结束恢复，设置失败会话级降级回播放器增益）；自由煲机维持播放器级增益。方案内本地音乐阶段切歌时，通知正文曲名即时刷新。
- Application 级播放控制器：进程存活期间后台持续播放，重新打开 App 直接恢复到进行中界面（含阶段与音源定位）。
- 进度持久化：每 60 秒 + 暂停/继续/结束/完成时落库（Room，`data/BurnInSession.kt`）；到达计划时长发出「煲机完成」系统通知（渠道 `burn_complete`，默认重要级；内容含定格的已煲时长，点击回到应用）并标记「已完成」，应用内弹一次 Snackbar。未授予通知权限时静默跳过。

### 屏幕保持（`MainActivity.kt` 屏幕保持协调器、`ui/settings/SettingsTab.kt`）

- **屏幕常亮**：入口仅在设置页「播放」分组（煲机页顶栏入口已移除），开启后播放界面保持常亮。
- **不息屏模式**：播放中保持亮屏，无操作达到系统息屏时长后降至最低亮度（避免部分机型息屏中断后台播放），触摸/暂停/结束即恢复；暂停或两开关全关时亮度交还系统。

### 外观（`ui/settings/SettingsTab.kt`、`ui/theme/`、`ui/BurnInApp.kt`）

- 深浅色：跟随系统 / 强制浅色 / 强制深色。
- 煲机页顶栏深浅色快捷切换（`ui/BurnInApp.kt`）：点击在浅色/深色间即时切换并持久化，与设置页「主题模式」写入同一配置、双向联动——当前深色则切浅色，反之切深色，「跟随系统」时按当前实际显示的深浅取反；浅色显示月亮图标（可切深色），深色显示太阳图标（可切浅色）。
- 动态取色：Android 12+ 跟随壁纸取色（默认开启，优先于预置调色盘；低版本开关置灰）。
- 6 套预置调色盘（默认「青瓷绿」），详见 docs/DESIGN.md。
- 视觉规格详见 docs/DESIGN.md。

### 多语言（`data/AppLanguage.kt`、`locale/AppLocale.kt`、`res/values-zh/`）

- 支持简体中文与英文；默认「跟随系统」自动检测（中文系统 → 中文，其余 → 英文）。
- 设置页「通用 → 语言」三选一：跟随系统 / 中文 / English；切换即时生效（更新进程内语言并重建界面），后台播放中的通知同步换语言。
- 应用内切换覆盖系统语言且重启后保持（DataStore 持久化）；Activity 与前台服务在 `attachBaseContext` 统一经 `AppLocale.wrap` 应用语言。
- 文案单一来源：全部用户可见文案入 `res/values/`（英文默认）与 `res/values-zh/`（中文）；方案/阶段/音源展示名由播放状态的结构化身份（planId/阶段身份 stageId/音源枚举）在展示层按语言解析（`ui/PlanDisplay.kt`），域层 `BurnPlan.name`/`BurnPhase.name` 仅为内部标识。

### 应用内更新（`update/`、`ui/update/`、`ui/settings/SettingsTab.kt`）

- **更新来源**：本仓库的 GitHub Release（`gbandszxc/open-burnin-tool`）。抓取 `https://github.com/<owner>/<repo>/releases/latest`（跟随重定向取 tag）与 `/releases/expanded_assets/<tag>`（取 APK 下载链接）判定最新版本，匿名访问公开页面，不需要账号、token 或任何配置（不经 GitHub API，因而无匿名限流）。
- **资产匹配**：按当前设备 ABI 匹配 Release 资产，优先 `arm64-v8a`，其次 `armeabi-v7a`；只匹配 release 变体（与本项目按 ABI 分包的 `-release.apk` 命名对应），不匹配 debug 包。
- **自动检查**：应用启动时静默检查一次（每个进程仅一次）；发现新版本弹窗询问「稍后 / 下载并安装」；已是最新或检查失败时静默不打扰。
- **手动检查**：设置页「关于」分组新增「检查更新」入口；点击后显示不可取消的「检查中」弹窗，结束后结果一律以弹窗展示、点确认关闭——「当前已是最新版本」「发现新版本但本机无适配安装包」「检查失败」（含原因）；发现适用于本机的新版本时则弹出「稍后 / 下载并安装」弹窗（含适用架构与安装包名）。
- **「稍后」策略**：三档 —— 本次（仅当前进程跳过下一次自动提示，不落库）、7 天（7 天内不再自动提示，到期自动失效）、下个版本（只跳过该版本，更高版本仍提示）。**手动检查更新不受「稍后」策略影响。**
- **下载与安装**：点「下载并安装」后显示下载进度弹窗（安装包名、进度条、实时网速、已下载/总大小），下载到应用缓存目录 `cacheDir/updates/`；完成后经 FileProvider 交给系统安装器安装，不静默安装，安装动作始终由用户在系统安装器上确认。下载失败会清理半成品文件并提示；「安装未知应用」权限未开启时引导用户到系统设置页开启。
- **权限**：新增 `INTERNET`（仅用于检查更新与下载更新包）与 `REQUEST_INSTALL_PACKAGES`（仅用于把更新包交给系统安装器）；其余功能仍然全程离线。
- **Debug 预览入口**：Debug 构建在设置页「关于」分组额外显示「预览更新提示」「预览下载进度」两项，仅用于不联网预览弹窗样式，Release 构建不显示。

## 交互要点

- **可续播**：标准/自定义方案有未完成会话时，方案卡显示「上次进度」，提供「继续」（从已完成秒数续播）与「全新开始」（旧检查点作废）双入口（`ui/burnin/BurnInIdleContent.kt`、`playback/PlaybackController.start`）。同一方案至多保留一个可续检查点。
- **阶段编排**：方案煲机配置卡内拖拽排序（长按手柄拖动，松手提交）、响度 1–100 行内校验、稳定阶段音乐开关与曲目勾选均即时保存（DataStore 记忆）；配置在点「开始/继续」时生效——开始与续播均按**当前配置**组装方案（续播沿用新顺序/响度/歌单，属记忆语义；方案 id 不随编排变化，续播会话匹配口径不受影响）（`playback/BurnInViewModel.startClassicPlan`/`startCustomPlan`）。
- **二次确认**：结束本次煲机（打开确认框即暂停并定格已煲秒数，取消自动恢复）、清除全部记录、移除本地音乐。
- **说明模式（InfoAction）**：多行说明性段落收进行尾 ⓘ 图标弹窗（煲机提示、自定义四阶段比例、不息屏模式说明），页面内只留单行功能性提示与校验错误（`ui/InfoDialog.kt`）。
- **输入校验**：小时数输入只允许数字、限 3 位；非法/越界行内错误提示并禁用开始；步进按钮到边界禁用。

## 非目标

- 不做账号体系与云同步。
- 不做流媒体/在线曲库，也不内置任何音频资产，只提供合成音源与用户主动导入的本地音乐。
- 响度策略边界：自由煲机增益一律走播放器级；方案煲机响度经系统媒体音量表达（仅限播放会话内按阶段比例设置档位，含设置失败自动降级回播放器增益），不监听、不拦截用户手动音量以外的系统行为（`PlaybackController`、`playback/SystemVolumeLoudness.kt`）。
- 不做社交、分享、排行榜等运营功能。

## 打包分发

- APK 按 ABI 分包：`armeabi-v7a` 与 `arm64-v8a` 两档，不产 universal 包（`app/build.gradle.kts` 的 `splits.abi`）。
- 产物命名：`open-burnin-tool-v<版本号>-<abi>-<debug|release>.apk`（如 `open-burnin-tool-v1.5.0-arm64-v8a-release.apk`），版本号由 `appVersionName` 单一来源驱动（`androidComponents.onVariants` 注入）。
- **发布流程（手动发布，无自动发布 workflow）**：
  1. 递增 `app/build.gradle.kts` 顶部的 `appVersionName`，并递增同处 `defaultConfig` 的 `versionCode`；
  2. `./gradlew assembleRelease`（产物在 `app/build/outputs/apk/release/`）；
  3. 在 GitHub 创建与 `appVersionName` 同名的 `v<版本号>` Release；
  4. 上传两个 ABI（`arm64-v8a`、`armeabi-v7a`）的 release APK 作为 Release 资产。
- **发布硬约束**（任一条写错都会让应用内更新静默失效）：
  - 标签/Release 版本号必须与 `appVersionName` 完全一致：应用端按资产名中的 `-v<版本号>-` 匹配（`update/ReleaseParsing.kt` 的 `findMatchingAsset`），版本号不符会匹配不到，被误报为「未找到适用于当前设备架构的安装包」。
  - 资产名不可改，必须是 `open-burnin-tool-v<版本号>-<abi>-release.apk`（由 `app/build.gradle.kts` 末尾的 `androidComponents.onVariants` 注入）；匹配规则还依赖其中的 `-release`（据此排除 debug 包）与对应 ABI 片段。
  - 必须递增 `versionCode`，否则系统安装器拒绝覆盖安装，应用内更新下载完也装不上。
  - 必须使用与应用已装版本相同的签名密钥构建，否则安装器报签名冲突。
- 应用内更新的数据来源即上述手动上传的双架构 Release 资产，其匹配依赖 `open-burnin-tool-v<版本号>-<abi>-release.apk` 命名规范。现有 CI `.github/workflows/build-apk.yml`（push 到 main 触发）只上传 Actions Artifact、不创建 Release，与发布无关。
