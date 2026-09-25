# PRODUCT.md — 产品定义（煲机助手）

> 依据当前 v1.5.0（versionCode 4，minSdk 26 / targetSdk 36）实际实现固化。功能或交互行为变更时须同步更新本文件。

## 产品定位

安卓耳机煲机工具（名称：中文「煲机助手」/ 英文「Burn-in Tool」，英文标识统一为 Burn-in Tool，不再使用 Burn-in Assistant）：用科学的声音信号（噪声/扫频）与用户自定义本地音乐让新耳机振膜快速进入稳定状态。全程离线、无账号、免费开源，适合拿到新耳机、想按方案或自由节奏煲机的个人用户。

## 核心功能清单

### 双路线煲机（煲机页，`ui/burnin/BurnInIdleContent.kt`）

- **方案煲机**（`BurnMode.PLAN`，`playback/BurnInViewModel.kt`）
  - 标准四阶段 · 120 小时：舒筋 12h 白噪（1/5 音量）→ 活络 12h 粉噪（1/3）→ 习武 72h 粉噪恒定（7/15）→ 打擂 24h 白噪↔粉噪每 30 分钟轮换（3/5）（`domain/model/BurnPlans.kt`；阶段时长/音量沿原版逆向结论，内置音乐音源已移除，音源为本版合成编排）。
  - 自定义四阶段：总时长 24–240 小时（默认 48，步进 ±12，`BurnInUiState.PLAN_CUSTOM_HOURS_RANGE/_STEP`），按 10/10/60/20 比例缩放到四阶段，打擂轮换周期保持 30 分钟（`BurnPlans.custom`）。
- **自由煲机**（`BurnMode.FREE`）
  - 音源任选：内置 7 合成音源或已导入的本地音乐（分组下拉，`ui/burnin/SoundSourceDropdown.kt`）。
  - 时长预设 2/8/16/24/48/72 小时（`BurnPlans.QUICK_HOURS`，默认 8h），或自定义 1–999 小时（预设与自定义互斥，`BurnInUiState.FREE_CUSTOM_HOURS_RANGE`）。
- 开始/暂停/继续/结束：播放中配置区整体被进度态替代（`ui/burnin/BurnInTab.kt` ActiveContent）。

### 音源（`domain/model/SoundSource.kt`、`playback/SynthPlayer.kt`）

- 7 种合成音源：正弦波 300Hz、粉红噪音（Paul Kellet 滤波 + 运行峰值归一化）、方波 150Hz、白噪音、低频扫频 100–200Hz（40s 循环）、混合煲机（白噪+粉噪各 50%）、宽频扫频 100Hz–10kHz（74s 循环）。UI 音效目录取 `SoundSource.catalog`（不含本地音源占位项）。
- 自定义本地音乐：系统文件选择器（SAF）导入音频文件，拷贝进应用私有目录并循环播放（`data/TrackRepository.kt`、`playback/BurnInViewModel.importTrack`）；支持移除（删文件 + 删记录，二次确认）。方案模型以 `SoundSource.LOCAL_TRACK`（不入 UI 目录）+ `BurnPhase.localTrackId` 表达，播放时 UI 显示曲目名。

### 进度记录（记录页，`ui/history/HistoryTab.kt`）

- 顶部小结：累计煲机 + 会话次数 + 清除入口。
- 分页列表：每页 20 条按开始时间倒序，滚近末尾自动追加，尾项提示「加载中 / 共 N 条」（`ui/history/HistoryViewModel.kt`，`data/BurnInRepository.sessionPage`）。
- 清除全部记录：二次确认弹窗（删记录 + 重置累计统计，不可恢复）。
- 会话行：时间 + 状态（进行中/已暂停/已完成/已结束）、方案与计划时长、实际已煲。

### 后台前台播放与通知控制（`playback/PlaybackController.kt`、`playback/BurnInService.kt`）

- 前台服务保活，通知常驻：播放中显示剩余时间倒计时（系统 chronometer），动作按钮暂停/继续、结束；点通知回到应用。
- 音频焦点：短暂丢失（来电等）暂停并在焦点回归后自动恢复；永久丢失保持暂停。拔耳机即暂停。
- Application 级播放控制器：进程存活期间后台持续播放，重新打开 App 直接恢复到进行中界面（含阶段与音源定位）。
- 进度持久化：每 60 秒 + 暂停/继续/结束/完成时落库（Room，`data/BurnInSession.kt`）；到达计划时长发出「煲机完成」系统通知（渠道 `burn_complete`，默认重要级；内容含定格的已煲时长，点击回到应用）并标记「已完成」，应用内弹一次 Snackbar。未授予通知权限时静默跳过。

### 屏幕保持（`MainActivity.kt` 屏幕保持协调器、`ui/settings/SettingsTab.kt`）

- **屏幕常亮**：煲机页顶栏与设置页同一开关，开启后播放界面保持常亮。
- **不息屏模式**：播放中保持亮屏，无操作达到系统息屏时长后降至最低亮度（避免部分机型息屏中断后台播放），触摸/暂停/结束即恢复；暂停或两开关全关时亮度交还系统。

### 外观（`ui/settings/SettingsTab.kt`、`ui/theme/`）

- 深浅色：跟随系统 / 强制浅色 / 强制深色。
- 动态取色：Android 12+ 跟随壁纸取色（默认开启，优先于预置调色盘；低版本开关置灰）。
- 6 套预置调色盘（默认「青瓷绿」），详见 docs/DESIGN.md。
- 视觉规格详见 docs/DESIGN.md。

### 多语言（`data/AppLanguage.kt`、`locale/AppLocale.kt`、`res/values-zh/`）

- 支持简体中文与英文；默认「跟随系统」自动检测（中文系统 → 中文，其余 → 英文）。
- 设置页「通用 → 语言」三选一：跟随系统 / 中文 / English；切换即时生效（更新进程内语言并重建界面），后台播放中的通知同步换语言。
- 应用内切换覆盖系统语言且重启后保持（DataStore 持久化）；Activity 与前台服务在 `attachBaseContext` 统一经 `AppLocale.wrap` 应用语言。
- 文案单一来源：全部用户可见文案入 `res/values/`（英文默认）与 `res/values-zh/`（中文）；方案/阶段/音源展示名由播放状态的结构化身份（planId/阶段序号/音源枚举）在展示层按语言解析（`ui/PlanDisplay.kt`），域层 `BurnPlan.name`/`BurnPhase.name` 仅为内部标识。

## 交互要点

- **可续播**：标准/自定义方案有未完成会话时，方案卡显示「上次进度」，提供「继续」（从已完成秒数续播）与「全新开始」（旧检查点作废）双入口（`ui/burnin/BurnInIdleContent.kt`、`playback/PlaybackController.start`）。同一方案至多保留一个可续检查点。
- **二次确认**：结束本次煲机（打开确认框即暂停并定格已煲秒数，取消自动恢复）、清除全部记录、移除本地音乐。
- **说明模式（InfoAction）**：多行说明性段落收进行尾 ⓘ 图标弹窗（煲机提示、自定义四阶段比例、不息屏模式说明），页面内只留单行功能性提示与校验错误（`ui/InfoDialog.kt`）。
- **输入校验**：小时数输入只允许数字、限 3 位；非法/越界行内错误提示并禁用开始；步进按钮到边界禁用。

## 非目标

- 不做账号体系与云同步。
- 不做流媒体/在线曲库，也不内置任何音频资产，只提供合成音源与用户主动导入的本地音乐。
- 不劫持系统媒体音量：增益一律走播放器级（`PlaybackController` 增益策略）。
- 不做社交、分享、排行榜等运营功能。

## 打包分发

- APK 按 ABI 分包：`armeabi-v7a` 与 `arm64-v8a` 两档，不产 universal 包（`app/build.gradle.kts` 的 `splits.abi`）。
- 产物命名：`burn-in-tool-v<版本号>-<abi>-<debug|release>.apk`（如 `burn-in-tool-v1.5.0-arm64-v8a-release.apk`），版本号由 `appVersionName` 单一来源驱动（`androidComponents.onVariants` 注入）。
