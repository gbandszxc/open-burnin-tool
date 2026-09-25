package com.github.gbandszxc.obt.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.playback.BurnInViewModel
import com.github.gbandszxc.obt.playback.TrackImportResult
import com.github.gbandszxc.obt.ui.burnin.BurnInTab
import com.github.gbandszxc.obt.ui.history.HistoryTab
import com.github.gbandszxc.obt.ui.history.HistoryViewModel
import com.github.gbandszxc.obt.ui.settings.SettingsTab
import com.github.gbandszxc.obt.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/** 底部导航的三个页签。 */
private enum class AppTab(
    val label: String,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    BURN("煲机", Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq),
    HISTORY("记录", Icons.Outlined.History, Icons.Filled.History),
    SETTINGS("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * 应用主骨架：单 Activity + 顶栏（标题 + 煲机页屏幕常亮开关）+ 底部三 Tab。
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

    val playbackState by burnViewModel.playbackState.collectAsStateWithLifecycle()
    val burnUiState by burnViewModel.uiState.collectAsStateWithLifecycle()
    val sessions by historyViewModel.sessions.collectAsStateWithLifecycle()
    val totalCompletedSeconds by historyViewModel.totalCompletedSeconds.collectAsStateWithLifecycle()
    val appSettings by settingsViewModel.settings.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.BURN) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    CompletionFeedback(sessions = sessions, snackbarHostState = snackbarHostState)
    // 本地音乐导入结果：行内提示（成功带曲目名，失败给重试建议）
    LaunchedEffect(burnViewModel) {
        burnViewModel.importEvents.collect { result ->
            val message = when (result) {
                is TrackImportResult.Success -> "已导入「${result.trackName}」"
                TrackImportResult.Failure -> "导入失败，请重新选择音频文件"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            AppTab.BURN -> "煲机助手"
                            AppTab.HISTORY -> "煲机记录"
                            AppTab.SETTINGS -> "设置"
                        },
                    )
                },
                actions = {
                    if (selectedTab == AppTab.BURN) {
                        // 煲机页多行插电/后台播放提示收进顶栏行尾 info 图标（原页脚说明已移除）
                        InfoAction(
                            title = "煲机提示",
                            description = "建议插电并保持耳机连接，煲机会在后台继续。",
                        )
                        // 与设置页同一字段（SettingsRepository.keepScreenOn）；FLAG 由 MainActivity 协调器应用
                        IconToggleButton(
                            checked = appSettings.keepScreenOn,
                            onCheckedChange = { checked ->
                                scope.launch { settingsViewModel.setKeepScreenOn(checked) }
                            },
                        ) {
                            Icon(
                                imageVector = if (appSettings.keepScreenOn) {
                                    Icons.Filled.LightMode
                                } else {
                                    Icons.Outlined.LightMode
                                },
                                contentDescription = if (appSettings.keepScreenOn) {
                                    "关闭屏幕常亮"
                                } else {
                                    "开启屏幕常亮"
                                },
                                tint = if (appSettings.keepScreenOn) {
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
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == tab) tab.filledIcon else tab.outlinedIcon,
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
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
                onStartPlan = burnViewModel::startPlan,
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
                modifier = contentModifier,
            )
        }
    }
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
        snackbarHostState.showSnackbar("本次煲机已完成")
    }
}
