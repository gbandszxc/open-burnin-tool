# DESIGN.md — 设计系统（煲机助手）

> 依据当前 v1.3.0 实际实现固化。任何 UI 样式改动须更新本文件对应条目。每条注明代码位置，便于同步维护。

## 设计原则

1. **克制中性表面 + 单一强调色**：界面以中性 surface 层级承载，强调只用 `colorScheme.primary` 一 种颜色（选中描边/竖条/主按钮/进度弧/状态行），无第二强调色。全文 M3 color scheme 角色取色，禁止硬编码颜色（各页面通用，参见 `ui/burnin/BurnInIdleContent.kt` PlanCard/CardHeader）。
2. **对比度正文 ≥ 4.5:1**：预置调色盘正文类角色（onSurface/onSurfaceVariant/on*Container vs 对应底色）按 WCAG ≥ 4.5:1 校验（`ui/theme/Color.kt` 文件头注释）。
3. **动效 ease-out 且尊重系统动画关闭**：所有补间统一 650ms `EaseOutCubic`；系统「动画时长缩放 = 0」时一律 `snap()` 直接跳变（`ui/burnin/ProgressRing.kt` `rememberAnimationsEnabled`/`easeOutSpec`，`ui/burnin/BurnInIdleContent.kt` `easeOutColorSpec`）。动效克制：步进数值淡入 160ms（`HoursStepperRow`）、校验错误行出现/消失 `animateContentSize()`（`PlanModeContent`）。
4. **说明性段落收进 InfoAction 弹窗**：多行说明一律收进行尾 ⓘ 图标弹窗，页面内只留单行功能性提示与校验错误（`ui/InfoDialog.kt`；用法见 `ui/BurnInApp.kt` 顶栏「煲机提示」、说明正文见 strings 资源（`info_custom_body`、`info_dim_body`、`info_classic_body`）。。
5. **选中态一律无对钩**：SegmentedButton 显式 `icon = {}`；卡片/色卡选中用主色描边 + tonal 底，不用对钩标记。

## 色彩

- **动态取色优先**：Android 12+ 开启动态取色时用 `dynamicLightColorScheme/dynamicDarkColorScheme` 跟随壁纸，优先于预置调色盘；低版本无此能力，开关置灰（`ui/theme/Theme.kt` `BurnInTheme`，开关在 `ui/settings/SettingsTab.kt` SwitchRow「动态取色」）。
- **6 套预置调色盘**（浅/深各一套完整 M3 scheme，`ui/theme/Color.kt` `ThemePalettes`）：
  1. 青瓷绿 `celadon`（品牌默认，`CeladonLight`/`CeladonDark`）
  2. 靛蓝 `indigo`（`IndigoLight`/`IndigoDark`）
  3. 琥珀暖橙 `amber`（`AmberLight`/`AmberDark`）
  4. 玫瑰红 `rose`（`RoseLight`/`RoseDark`）
  5. 森林绿 `forest`（`ForestLight`/`ForestDark`）
  6. 天青蓝 `cerulean`（`CeruleanLight`/`CeruleanDark`）
- 每套含浅/深两个 `ColorScheme`，按 M3 tonal 角色（primary=40/80、container=90/30、on*=10/90）调配；error 系沿用 Material 基准错误色。设置页色卡用 `palette.preview`（= 浅色 primary）。
- 深浅色模式：跟随系统/强制浅色/强制深色，解析口径 `Theme.kt` `resolveDarkTheme`（系统栏样式与其保持一致，`MainActivity.applyEdgeToEdgeStyle`）。
- 调色盘 id 持久化于 DataStore，默认 `celadon`（`data/SettingsRepository.kt` `DEFAULT_PALETTE_ID`；未知 id 回退首位）。

## 排版

- **默认系统字体家族、多字重**：不引入自定义字体；标题类（headlineSmall/titleLarge/titleMedium）统一 `FontWeight.SemiBold` 建立层级（`ui/theme/Type.kt` `AppTypography`）。
- **计时大数字 `tnum` 等宽**：displayLarge/Medium/Small 启用 `fontFeatureSettings = "tnum"`，煲机计时逐秒跳动不位移（`ui/theme/Type.kt`；用在 `ui/burnin/BurnInTab.kt` ActiveContent 的已煲计时）。
- **超长时长降字号规则**：计时 ≥ 1 天（跨天，格式 `d天 HH:mm:ss`）时，displaySmall 降为 30sp 保证单行放下（`ui/burnin/BurnInTab.kt` ActiveContent `crossDay` 分支；时长格式化在 `playback/TimeFormats.kt` `formatBurnDuration`）。
- 面向人的粗粒度时长文案（「3 天 2 小时」等）用 `ui/DurationFormats.kt` `formatDurationHuman`，仅用于累计小结等非计时场景。
- 计时文案一律 `maxLines = 1, softWrap = false`，禁换行。

## 组件规范

- **SegmentedButton（无对钩）**：`icon = {}` 显式去掉默认对钩，选中态仅 tonal 底 + 描边强调；label 强制单行（`maxLines = 1, softWrap = false`），超宽省略号。用于双路线切换与自由煲机时长预设（`ui/burnin/BurnInIdleContent.kt` `ModeSwitchRow`/`PresetHoursRow`）、主题模式与语言三选一（`ui/settings/SettingsTab.kt` `LanguageRow`）。
- **方案卡（PlanCard）**：整卡可点；圆角 16dp；未选中 1dp `outlineVariant` 描边 + `surfaceContainerLowest` 底，选中 2dp `primary` 描边 + `surfaceContainerLow` 底，描边颜色 `animateColorAsState` ease-out 过渡；卡头 3×20dp 主色竖条随选中强调；卡内开始按钮全宽 44dp（`ui/burnin/BurnInIdleContent.kt` `PlanCard`/`CardHeader`/`CardStartButton`）。
- **阶段编排区（StageArrangementSection）**：方案卡同款容器——16dp 圆角 + 1dp `outlineVariant` 描边 + `surfaceContainerLowest` 底，无选中态；卡头 3×20dp `primary` 竖条常强调 + 标题（titleSmall）+ 行尾 InfoAction ⓘ。阶段行四行等高 56dp（拖拽换位的几何前提）、形态统一，`surface` 底 12dp 圆角，行间距 8dp；行结构 = 48dp 拖拽手柄触控盒（`Icons.Filled.DragHandle` 24dp `onSurfaceVariant`）+ 位次小字（labelMedium）+ 阶段名（bodyLarge）/音源摘要（bodySmall）+ 行尾响度徽标（labelLarge `primary` 文字 + 1dp `outline` 描边 8dp 圆角，点击弹响度对话框）；稳定行徽标旁附 40dp 编辑图标按钮（`Icons.Filled.Edit` 20dp `onSurfaceVariant`），其余行以等宽占位保持四行行尾对齐。拖拽交互：`detectDragGesturesAfterLongPress` 只作用于手柄；被拖行 `zIndex` 抬升 + shadow 8dp + 1.02 缩放、位移直接跟手不走动画；被让位行按整行高平移做落点预览，让位位移、抬升起落与松手后的残余位移收尾回位统一走 650ms `EaseOutCubic`（系统动画关闭 `snap()`）；松手才提交新顺序上抛。响度对话框：与自定义总时长同款步进形态——40dp 圆形 −/+ 按钮（内置图标 20dp，`Icons.Filled.Remove`/`Add`）夹 160×48dp CompactNumberField（后缀 %），间距 12dp、水平居中；步进口径与 `stepPlanCustomHours` 同——草稿非法先回最近合法值（越界收敛到 1/100 边界、无数字回落打开时的生效值）再 ±5，范围 1–100、到边界按钮禁用，手动输入仍走 1–100 行内校验（错误 `bodySmall` `error` 色，非法禁用确定），步进/输入只改对话框草稿、确定才上抛持久化，「恢复默认」清除该阶段覆盖；−/+ 步进做 160ms 数值淡入（系统动画关闭 `snap()`）。稳定阶段播放内容弹窗（编辑图标打开，替代原行内子区）：标题「稳定阶段播放内容」，二档 SegmentedButton（无对钩，同 ModeSwitchRow 形态）+ 音乐档展开有序歌单——已勾选行首 `Icons.Filled.CheckCircle`（`primary` 20dp）+ 顺序序号徽标（labelSmall `primary` + 1dp `primary` 描边胶囊），未勾选 `Icons.Outlined.CheckCircle`（`onSurfaceVariant`）；行内最小高 44dp、行尾删除图标复用移除确认对话框；清单空或全未勾选时显示 `bodySmall` 提示行；底部「导入本地音乐…」行（`primary` 文字，导入中 16dp 进度圈 + 禁用）；歌单清单限高 320dp 纵向滚动。弹窗内变更全部即时生效（回调直上抛、DataStore 单一数据源回流），弹窗仅「关闭」动作按钮；弹窗开关与待删曲目 id 用 `rememberSaveable` 持有，旋转重建不丢（`ui/burnin/StageArrangementSection.kt`）。
- **步进器**：40dp 圆形 OutlinedIconButton（内置图标 20dp）夹 160×48dp 居中数字输入框，按钮与输入框间距 12dp；到边界按钮禁用；−/+ 步进做 160ms 数值淡入（`ui/burnin/BurnInIdleContent.kt` `HoursStepperRow`/`StepperIconButton`/`CompactNumberField`）。
- **紧凑数字输入框（CompactNumberField）**：48dp 高、12dp 圆角、1dp 细描边（错误态描边变 `error` 色）；数字与行尾单位小字整体居中；数字键盘单行；空值显示占位（`ui/burnin/BurnInIdleContent.kt` `CompactNumberField`）。
- **进度环（ProgressRing）**：播放态 272dp，弧宽 12dp 圆角端点，自 12 点方向顺时针；底部整圈 `surfaceContainerHighest` 轨道 + 顶部 `primary` 进度弧；进度变化 ease-out 平滑追随；progress ≤ 0 只画轨道（`ui/burnin/ProgressRing.kt`；尺寸在 `ui/burnin/BurnInTab.kt` ActiveContent）。
- **InfoAction（说明弹窗）**：行尾 24dp `Icons.Outlined.Info`（`onSurfaceVariant` 着色，按钮视觉 32dp、触达 ≥48dp），点击弹 `AlertDialog`（标题 = 设置项名，正文 = 说明全文，确认钮固定「知道了」）（`ui/InfoDialog.kt`）。
- **底部导航**：`NavigationBar` 三 Tab（煲机/记录/设置），选中 Filled 图标 + 未选中 Outlined 图标（`ui/BurnInApp.kt` `AppTab`/NavigationBar）。
- **顶栏（TopAppBar）**：标题随 Tab 切换（煲机助手/煲机记录/设置）；煲机页行尾固定「煲机提示」InfoAction + 屏幕常亮 IconToggleButton（选中 `primary`，未选中 `onSurfaceVariant`）（`ui/BurnInApp.kt`）。
- **开关行（SwitchRow）**：整行可点（toggleable，最小高 48dp），行尾 Switch 仅作状态展示避免双重响应；ⓘ 图标在开关左侧（`ui/settings/SettingsTab.kt` `SwitchRow`）。
- **记录列表**：小结行（两列数据 + 行尾清除 IconButton）+ `HorizontalDivider(outlineVariant)` + LazyColumn（内容 padding 水平 24dp）；行内边距垂直 12dp；状态色进行中/已暂停用 `primary`、其余 `onSurfaceVariant`（`ui/history/HistoryTab.kt` `SummaryRow`/`SessionRow`）。
- **空态**：48dp Outlined 图标 + 标题 + 一句说明，不堆插画（`ui/history/HistoryTab.kt` `EmptyHistory`）。
- **音效下拉（SoundSourceDropdown）**：收起态 44dp 只读触发行（与分段按钮行对齐）；展开菜单最高 380dp 内滚动，按「内置音效/本地音乐」分组；选中强调只用主色文字（无对钩）；本地音乐行尾删除图标（`ui/burnin/SoundSourceDropdown.kt`）。
- **对话框**：危险操作（结束煲机/清除记录/移除本地音乐）用 `AlertDialog` + 文本按钮；破坏性确认钮用 `error` 色（`ui/burnin/BurnInTab.kt`、`ui/history/HistoryTab.kt` `ClearConfirmDialog`、`ui/burnin/BurnInIdleContent.kt`）。
- **控制按钮**：播放态「暂停/继续」「结束」高 52dp、图标 24dp + 8dp 间距、`titleMedium` 文案，水平间距 12dp（`ui/burnin/BurnInTab.kt` ActiveContent）。
- **设置页动作行（ActionRow）**：整行可点（`clickable`，`fillMaxWidth` + 最小高 48dp），标题 `bodyLarge`/`onSurface`，行尾 `Icons.AutoMirrored.Outlined.KeyboardArrowRight` 20dp `onSurfaceVariant`、`contentDescription = null`（纯装饰指示，不单独响应）；可带一行 `bodySmall`/`onSurfaceVariant` 副标题（复用 `Caption`）。用于「关于」分组的「检查更新」，以及 Debug 构建专属的两条预览入口（`ui/settings/SettingsTab.kt` `ActionRow`）。
- **更新弹窗（UpdateHost）**：五态 `AlertDialog`，检查中与下载进度不可取消（`DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`），其余可取消。检查中 = 标题 + 说明 + 居中 `CircularProgressIndicator`；发现新版本 = 标题「发现新版本 x.y.z」+ 正文（匹配 ABI + 安装包名），确认钮「下载并安装」、取消钮「稍后」，外部/返回等同「稍后」进入选项；稍后三档 = 说明 + 三个整行可点选项（本次 / 7 天 / 下个版本，`bodyLarge` `primary`、最小高 48dp）；下载进度 = 文件名（单行省略）+ `LinearProgressIndicator`（高度与圆角走组件默认，确定态 `progress = { 比例 }` 并传 `drawStopIndicator = {}`，即不绘制轨道末端停止指示点，不确定态用默认不确定样式、本就不含停止点）+ 一行「速度 + 已下载/总量」（`bodySmall` `onSurfaceVariant`）+ 说明；检查结果通知 = 手动检查的三类结果（已是最新 / 无适配包 / 检查失败）统一在弹窗内展示，标题 + 正文（正文为空则不渲染）+ 确认钮「知道了」，确认/取消都只关闭弹窗（自动检查保持静默，不进入该态）。设置页「关于」分组的「检查更新」入口不变。速度/大小单位文案走字符串资源（`ui/update/UpdateHost.kt`）。

## 间距与形状

- **页面左右边距统一 24dp**：煲机页/设置页 `padding(horizontal = 24.dp)`，记录列表 `contentPadding` 水平 24dp（`ui/burnin/BurnInIdleContent.kt`、`ui/settings/SettingsTab.kt`、`ui/history/HistoryTab.kt`）。
- **间距节奏**：字段标签与控件 8–12dp；相关区块间 16–24dp；分组（SectionHeader）上下 20/10dp；卡片之间 12dp；页面首尾留白 8/24dp（各页面 Column 内 Spacer 用法）。
- **形状**：默认用 M3 组件默认圆角；自定义形状两档——卡片 16dp（PlanCard）、输入框 12dp（CompactNumberField）；色卡/竖条用 CircleShape / 2dp 小圆角（`ui/burnin/BurnInIdleContent.kt`、`ui/settings/SettingsTab.kt` `PaletteRow`）。
- **图标尺寸**：行内图标统一 24dp（`ui/burnin/BurnInTab.kt` `Icon24`）；信息/操作小图标 20dp（设置关于行、步进器内）；触达目标经 M3 最小交互目标保证 ≥ 48dp。
