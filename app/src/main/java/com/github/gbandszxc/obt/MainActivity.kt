package com.github.gbandszxc.obt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.gbandszxc.obt.locale.AppLocale
import com.github.gbandszxc.obt.playback.PlaybackStatus
import com.github.gbandszxc.obt.ui.BurnInApp
import com.github.gbandszxc.obt.ui.theme.BurnInTheme
import com.github.gbandszxc.obt.ui.theme.resolveDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 单 Activity 入口：全部界面为 Compose（[BurnInApp] 三 Tab 骨架）。
 * 播放状态由 Application 级 PlaybackController 持有，Activity 重建/重进不丢。
 * 主题（深浅色模式/调色盘/动态取色）来自 [SettingsRepository]：
 * 首帧同步读初值保证不闪烁，之后经 flow 持续收集、设置变更即时生效。
 *
 * 屏幕保持（屏幕常亮 + 不息屏模式）的窗口 FLAG 与亮度也由本 Activity 统一协调，
 * 全应用只有这一处写 FLAG_KEEP_SCREEN_ON / screenBrightness（状态机见 [runScreenKeepCoordinator]）。
 */
class MainActivity : ComponentActivity() {

    /** 用户最近一次触摸时刻（elapsedRealtime 毫秒）。仅在主线程写入，StateFlow 供协调流即时读取。 */
    private val lastInteractionElapsed = MutableStateFlow(SystemClock.elapsedRealtime())

    /** 最近一次实际应用到窗口的屏幕保持状态，用于按变化应用窗口操作（窗口默认无 FLAG、亮度由系统接管）。 */
    private var appliedScreenState = WindowScreenState(keepFlag = false, dim = false)

    /** 33+ 通知运行时权限：拒绝只影响通知与通知栏按钮，播放与音频焦点不受影响。 */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 无需处理：拒绝时前台服务照常运行，仅通知不展示
        }

    /** 语言切换（含冷启动）：把进程级应用语言应用到 Activity 资源，UI 字符串据此解析。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        val settingsRepository = (application as BurnInApplication).appContainer.settingsRepository
        setContent {
            // 首帧同步读取一次（本地文件毫秒级），首帧即用户保存的主题，不出现浅/深闪烁
            val initialSettings = remember { runBlocking { settingsRepository.snapshotOnce() } }
            val settings by produceState(initialValue = initialSettings) {
                settingsRepository.themeSettings.collect { value = it }
            }
            val darkTheme = resolveDarkTheme(settings.themeMode)

            // 系统栏图标对比度跟随应用主题（而非系统设置）：强制浅色/深色与系统相反时同样正确
            DisposableEffect(darkTheme) {
                applyEdgeToEdgeStyle(darkTheme)
                onDispose { }
            }

            BurnInTheme(
                themeMode = settings.themeMode,
                paletteId = settings.paletteId,
                dynamicColor = settings.dynamicColor,
            ) {
                BurnInApp()
            }
        }
        startScreenKeepCoordinator()
    }

    /** 任意触摸（含 DOWN/MOVE/UP）都视为一次用户交互：不息屏模式的「无操作计时」归零。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastInteractionElapsed.value = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(event)
    }

    /**
     * 启动屏幕保持协调器：STARTED 期间运行，离开前台自动挂起（亮度在挂起时交还系统）。
     */
    private fun startScreenKeepCoordinator() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runScreenKeepCoordinator()
            }
        }
    }

    /**
     * 屏幕保持协调器：把「屏幕常亮」「不息屏模式」「播放状态」「用户交互」四个输入
     * 合并为窗口的两个正交目标维度（KEEP_SCREEN_ON 标志 / 最低亮度），按变化应用并打日志。
     *
     * ```
     * 输入（事件驱动 + 每秒心跳各查一次）
     *   keepScreenOn（设置）──┐
     *   dimKeepAlive（设置）──┤
     *   status == PLAYING  ──┼──► 目标状态推导 ──► 按变化应用（主线程，单处写窗口）
     *   lastInteraction    ──┤
     *   1s 心跳(now)       ──┘
     *
     *   keepFlag = keepScreenOn || (dimKeepAlive && PLAYING)
     *   dim      = dimKeepAlive && PLAYING && (now - lastInteraction ≥ 系统息屏时长)
     *
     * 真值表：
     *   常亮开                    → FLAG 加，不调暗（用户显式要求常亮，不降亮度）
     *   常亮关 + 不息屏开 + 播放中  → FLAG 加；无操作达到系统息屏时长后亮度 0.01f，触摸/暂停即恢复
     *   其余（暂停/空闲/两开关全关）→ FLAG 清，亮度 -1f 交还系统
     *
     * 状态机（keepFlag × dim 四态，只允许图中箭头所示的迁移，均整帧原子应用 + 日志）：
     *
     *   ┌─────────┐ 常亮开或不息屏生效   ┌─────────┐ 无操作≥息屏时长 ┌──────────┐
     *   │ 无FLAG   │ ─────────────────► │ 常亮全亮 │ ─────────────► │ 常亮调暗  │
     *   │ 系统亮度  │ ◄───────────────── │ (T, F)  │ ◄───────────── │ (T, T)   │
     *   └─────────┘  两开关关/暂停/触摸  └─────────┘  触摸/暂停/结束  └──────────┘
     *
     * 交互不冲突的关键：FLAG 是否存在永远先算「两功能的并集」，清一个开关不会误伤另一个——
     * 常亮关但不息屏播放中时 FLAG 保留；dim 变 false 先于/伴随 FLAG 清除发生在同一帧，
     * 不会出现「FLAG 已清、亮度仍是 0.01f」的错序状态。
     * ```
     */
    private suspend fun runScreenKeepCoordinator() {
        val container = (application as BurnInApplication).appContainer
        // 回到前台即视为一次交互：冷启动恢复播放/切回应用时不因陈旧的触摸时刻立即调暗
        lastInteractionElapsed.value = SystemClock.elapsedRealtime()
        Log.i(TAG, "屏幕保持协调器启动（STARTED）")
        try {
            combine(
                container.settingsRepository.keepScreenOn,
                container.settingsRepository.dimKeepAlive,
                container.playbackController.state,
                lastInteractionElapsed,
                elapsedTicker(),
            ) { keepScreenOn, dimKeepAlive, playback, lastInteraction, now ->
                ScreenKeepInputs(
                    keepScreenOn = keepScreenOn,
                    dimKeepAlive = dimKeepAlive,
                    playing = playback.status == PlaybackStatus.PLAYING,
                    lastInteraction = lastInteraction,
                    now = now,
                )
            }
                .map { inputs ->
                    // 仅在可能调暗时才读系统息屏时长（ContentResolver 跨进程读取），其余分支零开销
                    val timeoutMillis = if (inputs.dimKeepAlive && inputs.playing) {
                        screenOffTimeoutMillis()
                    } else {
                        DEFAULT_SCREEN_OFF_TIMEOUT_MILLIS
                    }
                    inputs to WindowScreenState(
                        keepFlag = inputs.keepScreenOn || (inputs.dimKeepAlive && inputs.playing),
                        dim = inputs.dimKeepAlive &&
                            inputs.playing &&
                            inputs.now - inputs.lastInteraction >= timeoutMillis,
                    )
                }
                .distinctUntilChangedBy { it.second }
                .collect { (inputs, target) -> applyScreenState(inputs, target) }
        } finally {
            // 离开 STARTED（Home/熄屏/销毁）：亮度交还系统；FLAG 留待回到前台由协调器重新评估
            restoreSystemBrightness()
        }
    }

    /** 把目标状态按变化应用到窗口：先调亮度后动 FLAG，全程主线程单帧完成并记录日志。 */
    private fun applyScreenState(inputs: ScreenKeepInputs, target: WindowScreenState) {
        val currentWindow = window ?: return
        if (target.dim != appliedScreenState.dim) {
            currentWindow.attributes = currentWindow.attributes.also {
                it.screenBrightness = if (target.dim) DIM_BRIGHTNESS else BRIGHTNESS_FOLLOW_SYSTEM
            }
        }
        if (target.keepFlag != appliedScreenState.keepFlag) {
            if (target.keepFlag) {
                currentWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                currentWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (target != appliedScreenState) {
            Log.i(
                TAG,
                "屏幕保持切换: 常亮=${inputs.keepScreenOn} 不息屏=${inputs.dimKeepAlive} " +
                    "播放中=${inputs.playing} 无操作=${(inputs.now - inputs.lastInteraction) / 1000}s" +
                    " → FLAG_KEEP_SCREEN_ON=${target.keepFlag} 最低亮度=${target.dim}",
            )
        }
        appliedScreenState = target
    }

    /** 挂起/销毁时恢复：亮度交还系统（-1f），FLAG 不在此处动（避免误清常亮的合理状态）。 */
    private fun restoreSystemBrightness() {
        val currentWindow = window ?: return
        currentWindow.attributes = currentWindow.attributes.also {
            it.screenBrightness = BRIGHTNESS_FOLLOW_SYSTEM
        }
        appliedScreenState = appliedScreenState.copy(dim = false)
        Log.i(TAG, "离开前台：亮度交还系统，FLAG 留待回前台重新评估")
    }

    /** 系统息屏时长（毫秒）：读取失败（极少数机型）回退 30s 默认值。 */
    private fun screenOffTimeoutMillis(): Long = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toLong()
    } catch (e: Exception) {
        Log.w(TAG, "读取系统息屏时长失败，回退默认 30s", e)
        DEFAULT_SCREEN_OFF_TIMEOUT_MILLIS
    }

    /** 每秒发出当前 elapsedRealtime 的心跳流：驱动「无操作时长」周期检查；其余输入变化则即时响应，不等心跳。 */
    private fun elapsedTicker(): Flow<Long> = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(SCREEN_CHECK_INTERVAL_MILLIS)
        }
    }

    /**
     * 按应用解析出的深浅色重设系统栏样式：
     * 状态栏透明、导航栏沿用 androidx.activity 默认 scrim（仅 API 28- 的三键导航需要）。
     */
    private fun applyEdgeToEdgeStyle(darkTheme: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(
                LIGHT_NAV_BAR_SCRIM,
                DARK_NAV_BAR_SCRIM,
            ) { darkTheme },
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        // 与 androidx.activity.EdgeToEdge 默认导航栏 scrim 一致（API 28 及以下三键导航对比度）
        val LIGHT_NAV_BAR_SCRIM = AndroidColor.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_BAR_SCRIM = AndroidColor.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}

/** 协调器输入快照：两开关 + 是否播放中 + 交互/心跳时刻。 */
private data class ScreenKeepInputs(
    val keepScreenOn: Boolean,
    val dimKeepAlive: Boolean,
    val playing: Boolean,
    val lastInteraction: Long,
    val now: Long,
)

/** 窗口目标状态：KEEP_SCREEN_ON 标志与「最低亮度」两个正交维度。 */
private data class WindowScreenState(
    val keepFlag: Boolean,
    val dim: Boolean,
)

/** 不息屏状态检查心跳间隔（毫秒）。 */
private const val SCREEN_CHECK_INTERVAL_MILLIS = 1_000L

/** 调暗后的最低亮度（window.screenBrightness 合法区间 0..1）。 */
private const val DIM_BRIGHTNESS = 0.01f

/** screenBrightness 的哨兵值 -1f：亮度交还系统接管。 */
private const val BRIGHTNESS_FOLLOW_SYSTEM = -1f

/** 系统息屏时长读取失败时的兜底值（毫秒）。 */
private const val DEFAULT_SCREEN_OFF_TIMEOUT_MILLIS = 30_000L

private const val TAG = "ScreenKeep"
