package com.github.gbandszxc.obt.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.data.AppLanguage
import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.data.ThemeMode
import com.github.gbandszxc.obt.locale.AppLocale
import com.github.gbandszxc.obt.playback.BurnInViewModel
import com.github.gbandszxc.obt.playback.TrackImportResult
import com.github.gbandszxc.obt.ui.burnin.BurnInTab
import com.github.gbandszxc.obt.ui.history.HistoryTab
import com.github.gbandszxc.obt.ui.history.HistoryViewModel
import com.github.gbandszxc.obt.ui.settings.SettingsTab
import com.github.gbandszxc.obt.ui.settings.SettingsViewModel
import com.github.gbandszxc.obt.ui.theme.resolveDarkTheme
import com.github.gbandszxc.obt.ui.update.UpdateHost
import com.github.gbandszxc.obt.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

/** 底部导航的三个页签（label 资源 id，展示时按应用语言解析）。 */
private enum class AppTab(
    val labelRes: Int,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    BURN(R.string.tab_burn, Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq),
    HISTORY(R.string.tab_history, Icons.Outlined.History, Icons.Filled.History),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** 从 Compose 树的 context 沿包装链找宿主 Activity（语言切换后重建界面用）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 应用主骨架：单 Activity + 顶栏（标题 + 煲机页深浅色主题切换）+ 底部三 Tab。
 *
 * - 播放状态来自 Application 级 [PlaybackController]（经 [BurnInViewModel]），
 *   后台播放中重新打开 App 直接恢复到进行中界面；
 * - 屏幕常亮等设置经 [SettingsViewModel] 读写 [com.github.gbandszxc.obt.data.SettingsRepository]，
 *   与设置页共用同一数据源；窗口 FLAG/亮度统一由 MainActivity 的协调器管理，UI 层不碰窗口；
 * - 会话完成反馈：监听 Room 会话流，出现「本次运行期间完成」的会话时弹一次 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BurnInApp() {
    val app = LocalContext.current.applicationContext as BurnInApplication
    val burnViewModel: BurnInViewModel = viewModel(factory = BurnInViewModel.factory(app))
    val historyViewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(app))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.factory(app))

    val playbackState by burnViewModel.playbackState.collectAsStateWithLifecycle()
    val burnUiState by burnViewModel.uiState.collectAsStateWithLifecycle()
    val sessions by historyViewModel.sessions.collectAsStateWithLifecycle()
    val totalCompletedSeconds by historyViewModel.totalCompletedSeconds.collectAsStateWithLifecycle()
    val appSettings by settingsViewModel.settings.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.BURN) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    CompletionFeedback(sessions = sessions, snackbarHostState = snackbarHostState)
    // 本地音乐导入结果：行内提示（成功带曲目名，失败给重试建议）。
    // 重建后本 effect 重新收集并捕获新语言 context，提示文案跟随应用语言
    val context = LocalContext.current
    LaunchedEffect(burnViewModel) {
        burnViewModel.importEvents.collect { result ->
            val message = when (result) {
                is TrackImportResult.Success -> context.getString(R.string.msg_import_success, result.trackName)
                TrackImportResult.Failure -> context.getString(R.string.msg_import_failure)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // 应用启动静默检查一次更新（每进程仅一次；有新版本且未被「稍后」跳过时才弹窗）
    LaunchedEffect(Unit) { updateViewModel.checkOnAppStart() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (selectedTab) {
                                AppTab.BURN -> R.string.title_burn
                                AppTab.HISTORY -> R.string.title_history
                                AppTab.SETTINGS -> R.string.title_settings
                            },
                        ),
                    )
                },
                actions = {
                    if (selectedTab == AppTab.BURN) {
                        // 煲机页多行插电/后台播放提示收进顶栏行尾 info 图标（原页脚说明已移除）
                        InfoAction(
                            title = stringResource(R.string.top_info_burn_title),
                            description = stringResource(R.string.top_info_burn_body),
                        )
                        // 与设置页同一字段（SettingsRepository.themeMode）；按实际生效深浅在 LIGHT/DARK 间切换
                        val isDarkTheme = resolveDarkTheme(appSettings.themeMode)
                        IconToggleButton(
                            checked = isDarkTheme,
                            // 回调参数是切换后的新状态（内部 onCheckedChange(!checked)），
                            // 语义易误用；这里不依赖参数，直接按当前生效深浅取反求目标模式。
                            onCheckedChange = {
                                val target = if (isDarkTheme) ThemeMode.LIGHT else ThemeMode.DARK
                                scope.launch { settingsViewModel.setThemeMode(target) }
                            },
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) {
                                    Icons.Outlined.LightMode
                                } else {
                                    Icons.Outlined.DarkMode
                                },
                                contentDescription = stringResource(
                                    if (isDarkTheme) {
                                        R.string.cd_theme_switch_to_light
                                    } else {
                                        R.string.cd_theme_switch_to_dark
                                    },
                                ),
                                tint = if (isDarkTheme) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val tabLabel = stringResource(tab.labelRes)
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == tab) tab.filledIcon else tab.outlinedIcon,
                                contentDescription = tabLabel,
                            )
                        },
                        label = { Text(tabLabel) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (selectedTab) {
            AppTab.BURN -> BurnInTab(
                uiState = burnUiState,
                playbackState = playbackState,
                totalCompletedSeconds = totalCompletedSeconds,
                onModeChange = burnViewModel::setMode,
                onPlanCardChange = burnViewModel::setPlanCard,
                onPlanCustomHoursChange = burnViewModel::setPlanCustomHours,
                onStepPlanCustomHours = burnViewModel::stepPlanCustomHours,
                onStartClassic = burnViewModel::startClassicPlan,
                onStartCustom = burnViewModel::startCustomPlan,
                onStageOrderChange = burnViewModel::setStageOrder,
                onStageGainChange = burnViewModel::setStageGain,
                onSteadyMusicEnabledChange = burnViewModel::setSteadyMusicEnabled,
                onToggleSteadyTrack = burnViewModel::toggleSteadyTrack,
                onImportSteadyTrack = burnViewModel::importSteadyTrack,
                onFreeSoundChange = burnViewModel::setFreeSound,
                onFreePresetHoursChange = burnViewModel::setFreePresetHours,
                onFreeCustomHoursChange = burnViewModel::setFreeCustomHours,
                onStartFree = burnViewModel::startFree,
                onImportTrack = burnViewModel::importTrack,
                onDeleteTrack = burnViewModel::deleteTrack,
                onPause = burnViewModel::pause,
                onResume = burnViewModel::resume,
                onStop = burnViewModel::stop,
                modifier = contentModifier,
            )
            AppTab.HISTORY -> HistoryTab(
                sessions = sessions,
                totalCompletedSeconds = totalCompletedSeconds,
                modifier = contentModifier,
            )
            AppTab.SETTINGS -> SettingsTab(
                state = appSettings,
                onThemeModeChange = { mode ->
                    scope.launch { settingsViewModel.setThemeMode(mode) }
                },
                onDynamicColorChange = { enabled ->
                    scope.launch { settingsViewModel.setDynamicColor(enabled) }
                },
                onPaletteChange = { id ->
                    scope.launch { settingsViewModel.setPaletteId(id) }
                },
                onKeepScreenOnChange = { enabled ->
                    scope.launch { settingsViewModel.setKeepScreenOn(enabled) }
                },
                onDimKeepAliveChange = { enabled ->
                    scope.launch { settingsViewModel.setDimKeepAlive(enabled) }
                },
                onLanguageChange = { language ->
                    // 先同步更新进程内语言（重建后的界面与通知立刻生效），再落盘；
                    // 语言是全局配置，重建整个界面最直接，也保证 Compose 全部文案重新解析
                    AppLocale.update(language)
                    scope.launch {
                        settingsViewModel.setLanguage(language)
                        context.findActivity()?.recreate()
                    }
                },
                onCheckUpdate = { updateViewModel.checkManually() },
                onPreviewUpdatePrompt = { updateViewModel.previewUpdatePrompt() },
                onPreviewDownloadProgress = { updateViewModel.previewDownloadProgress() },
                modifier = contentModifier,
            )
        }
    }

    // 更新弹窗宿主：置于 Scaffold 之后同一层级，弹窗覆盖在最上层
    UpdateHost(viewModel = updateViewModel)
}

/**
 * 完成反馈：应用运行期间出现新的「已完成」会话时弹一次 Snackbar。
 * 以应用启动时刻为门槛过滤历史会话，冷启动恢复列表不触发。
 */
@Composable
private fun CompletionFeedback(
    sessions: List<BurnInSession>,
    snackbarHostState: SnackbarHostState,
) {
    val completedMessage = stringResource(R.string.msg_session_completed)
    val appStartMillis = remember { System.currentTimeMillis() }
    var announcedSessionId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sessions) {
        val newlyCompleted = sessions
            .filter {
                it.status == SessionStatus.COMPLETED &&
                    it.lastUpdatedAt >= appStartMillis &&
                    it.id != announcedSessionId
            }
            .maxByOrNull { it.lastUpdatedAt } ?: return@LaunchedEffect
        announcedSessionId = newlyCompleted.id
        snackbarHostState.showSnackbar(completedMessage)
    }
}
