package com.github.gbandszxc.obt.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.gbandszxc.obt.BuildConfig
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.update.DownloadProgress
import com.github.gbandszxc.obt.update.GitHubReleaseChecker
import com.github.gbandszxc.obt.update.UpdateCheckResult
import com.github.gbandszxc.obt.update.UpdateDownloader
import com.github.gbandszxc.obt.update.UpdateInfo
import com.github.gbandszxc.obt.update.UpdateSnoozeStore
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 更新流程的界面状态。 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    /** 正在检查（仅手动检查会进入该状态，自动检查保持静默）。 */
    data object Checking : UpdateUiState

    data class Available(val info: UpdateInfo, val showSnoozeOptions: Boolean = false) : UpdateUiState

    data class Downloading(val info: UpdateInfo, val progress: DownloadProgress) : UpdateUiState
}

/** 一次性事件：瞬态提示与安装请求。 */
sealed interface UpdateEvent {
    /** 瞬态提示（Toast 文案，已本地化）。 */
    data class Message(val text: String) : UpdateEvent

    /** 下载完成，交由界面发起安装。 */
    data class Install(val apk: File) : UpdateEvent
}

/**
 * 应用内更新的界面状态机：检查 / 下载 / 「稍后」三档 / 调试预览。
 *
 * 自动检查（[checkOnAppStart]）全程静默，只在「有新版本且未被稍后策略跳过」时弹窗；
 * 手动检查（[checkManually]）给出完整反馈，且不受「稍后」策略影响。
 * 联网与写盘都委托 [GitHubReleaseChecker] / [UpdateDownloader]（内部已在 IO 线程），
 * 进度回调由下载器从 IO 线程发出，这里直接给 [MutableStateFlow.value] 赋值即可（线程安全）。
 */
class UpdateViewModel(
    private val application: BurnInApplication,
    private val checker: GitHubReleaseChecker,
    private val downloader: UpdateDownloader,
    private val snoozeStore: UpdateSnoozeStore,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    /** 弹窗状态。 */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    // 用 Channel 而非 SharedFlow(replay=0)：后者无订阅者时不投递，配置变更/重组空档会丢掉
    // UpdateEvent.Install，表现为「下载完成却没弹安装器」；Channel.BUFFERED 会暂存到有订阅者为止。
    private val _events = Channel<UpdateEvent>(Channel.BUFFERED)

    /** 一次性事件（Toast 提示与安装请求）。 */
    val events: Flow<UpdateEvent> = _events.receiveAsFlow()

    /**
     * 当前设备匹配用的 ABI：弹窗文案要显示它，而 [UpdateInfo] 不含 ABI 字段，故由这里透出。
     */
    val currentAbi: String
        get() = checker.currentAbi

    /**
     * 预览态标记：预览弹窗下的「下载并安装」只提示、不真的联网下载。
     * 从 IO 回调线程与主线程都可能读写，用 @Volatile 保证可见性。
     */
    @Volatile
    private var previewOnly = false

    /** 启动时静默检查一次（每进程仅一次；已是最新或失败都不打扰用户）。 */
    fun checkOnAppStart() {
        // 每进程仅一次的守卫：进程级静态语义（与参考实现一致），配置变更/重建不会重复打扰用户
        if (autoCheckStarted) return
        autoCheckStarted = true
        viewModelScope.launch {
            // 自动检查全程不置 Checking：失败/最新/无匹配包一律静默回到 Idle
            val result = try {
                checker.check()
            } catch (e: CancellationException) {
                // 协程取消（ViewModel 清理）必须原样重抛，不能与检查失败混为一谈
                throw e
            } catch (e: Exception) {
                return@launch
            }
            when (result) {
                UpdateCheckResult.UpToDate -> Unit
                is UpdateCheckResult.Unsupported -> Unit
                is UpdateCheckResult.Available -> {
                    val skip = try {
                        snoozeStore.shouldSkipAutomaticPrompt(result.info.versionName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (!skip) {
                        previewOnly = false
                        _state.value = UpdateUiState.Available(result.info)
                    }
                }
            }
        }
    }

    /** 设置页手动检查：给出完整反馈。 */
    fun checkManually() {
        if (_state.value == UpdateUiState.Checking) return
        // 真实检查一律清除预览态，避免预览后残留的标记让后续真实下载被误判为预览
        previewOnly = false
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            try {
                when (val result = checker.check()) {
                    UpdateCheckResult.UpToDate -> {
                        _state.value = UpdateUiState.Idle
                        _events.send(UpdateEvent.Message(application.getString(R.string.update_latest_version)))
                    }
                    // 手动检查不受「稍后」策略影响：既然用户主动点了，就直接展示
                    is UpdateCheckResult.Available -> _state.value = UpdateUiState.Available(result.info)
                    is UpdateCheckResult.Unsupported -> {
                        _state.value = UpdateUiState.Idle
                        _events.send(
                            UpdateEvent.Message(
                                application.getString(R.string.update_no_matching_asset, result.versionName),
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Idle
                _events.send(UpdateEvent.Message(networkMessage(R.string.update_check_failed, e)))
            }
        }
    }

    /** 下载并安装当前 [UpdateUiState.Available] 指向的安装包。 */
    fun downloadAndInstall() {
        val available = _state.value as? UpdateUiState.Available ?: return
        if (previewOnly) {
            // 预览态：只演示交互，绝不真的联网下载/安装
            _state.value = UpdateUiState.Idle
            previewOnly = false
            viewModelScope.launch {
                _events.send(UpdateEvent.Message(application.getString(R.string.settings_preview_update_prompt_desc)))
            }
            return
        }
        val info = available.info
        _state.value = UpdateUiState.Downloading(info, DownloadProgress(0L, info.sizeBytes, 0L))
        viewModelScope.launch {
            try {
                val apk = downloader.download(info) { progress ->
                    _state.value = UpdateUiState.Downloading(info, progress)
                }
                _state.value = UpdateUiState.Idle
                _events.send(UpdateEvent.Install(apk))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Idle
                _events.send(UpdateEvent.Message(networkMessage(R.string.update_download_failed, e)))
            }
        }
    }

    /** 「稍后」→ 展开三档选项。 */
    fun requestSnoozeOptions() {
        val current = _state.value as? UpdateUiState.Available ?: return
        if (!current.showSnoozeOptions) _state.value = current.copy(showSnoozeOptions = true)
    }

    fun snoozeOnce() {
        val info = (_state.value as? UpdateUiState.Available)?.info ?: return
        if (previewOnly) {
            // 预览态只关闭弹窗，绝不落库：否则假版本（9.9.9-preview）会写进存储，
            // 7 天策略不比对版本，会把真实的更新提示压制 7 天
            previewOnly = false
            _state.value = UpdateUiState.Idle
            return
        }
        snoozeStore.snoozeOnce(info.versionName)
        _state.value = UpdateUiState.Idle
    }

    fun snoozeForSevenDays() {
        val info = (_state.value as? UpdateUiState.Available)?.info ?: return
        if (previewOnly) {
            previewOnly = false
            _state.value = UpdateUiState.Idle
            return
        }
        viewModelScope.launch {
            snoozeStore.snoozeForSevenDays(info.versionName)
            _state.value = UpdateUiState.Idle
        }
    }

    fun snoozeUntilNextVersion() {
        val info = (_state.value as? UpdateUiState.Available)?.info ?: return
        if (previewOnly) {
            previewOnly = false
            _state.value = UpdateUiState.Idle
            return
        }
        viewModelScope.launch {
            snoozeStore.snoozeUntilNextVersion(info.versionName)
            _state.value = UpdateUiState.Idle
        }
    }

    /** 关闭当前弹窗，回到 [UpdateUiState.Idle]。 */
    fun dismiss() {
        _state.value = UpdateUiState.Idle
        previewOnly = false
    }

    /** 仅 Debug：预览「发现新版本」弹窗（不联网、不下载、不保存）。 */
    fun previewUpdatePrompt() {
        if (!BuildConfig.DEBUG) return
        previewOnly = true
        _state.value = UpdateUiState.Available(previewInfo(sizeBytes = 0L), showSnoozeOptions = false)
    }

    /** 仅 Debug：预览下载进度弹窗（假进度，不联网、不安装）。 */
    fun previewDownloadProgress() {
        if (!BuildConfig.DEBUG) return
        previewOnly = false
        viewModelScope.launch {
            val info = previewInfo(sizeBytes = PREVIEW_TOTAL_BYTES)
            _state.value = UpdateUiState.Downloading(info, DownloadProgress(0L, PREVIEW_TOTAL_BYTES, 0L))
            var downloaded = 0L
            while (downloaded < PREVIEW_TOTAL_BYTES) {
                delay(PREVIEW_STEP_MS)
                // 速度在 3–5MB/s 间波动，按步进时长折算本次前进量，跑满总量即收尾
                val speed = Random.nextLong(PREVIEW_SPEED_MIN, PREVIEW_SPEED_MAX)
                val advance = (speed * PREVIEW_STEP_MS / 1000L).coerceAtLeast(1L)
                downloaded = (downloaded + advance).coerceAtMost(PREVIEW_TOTAL_BYTES)
                _state.value = UpdateUiState.Downloading(
                    info,
                    DownloadProgress(downloaded, PREVIEW_TOTAL_BYTES, speed),
                )
            }
            _state.value = UpdateUiState.Idle
        }
    }

    /** 统一的失败文案：优先带异常信息，缺失时退回「网络不可用」。 */
    private fun networkMessage(resId: Int, e: Exception): String = application.getString(
        resId,
        e.message ?: application.getString(R.string.update_network_unavailable),
    )

    /** 预览用假版本信息；ABI 取真实匹配结果，让弹窗文案与真机一致。 */
    private fun previewInfo(sizeBytes: Long): UpdateInfo = UpdateInfo(
        versionName = PREVIEW_VERSION_NAME,
        assetName = "open-burnin-tool-v$PREVIEW_VERSION_NAME-${checker.currentAbi}-release.apk",
        downloadUrl = "https://example.invalid/preview.apk",
        sizeBytes = sizeBytes,
    )

    companion object {
        /** 自动检查是否已启动（进程级，重建界面不重复触发）。 */
        private var autoCheckStarted = false

        private const val PREVIEW_VERSION_NAME = "9.9.9-preview"

        /** 预览下载总量：42MB，接近真实 APK 的直观数字。 */
        private const val PREVIEW_TOTAL_BYTES = 42L * 1024L * 1024L

        /** 预览进度步进间隔。 */
        private const val PREVIEW_STEP_MS = 100L

        private const val PREVIEW_SPEED_MIN = 3L * 1024L * 1024L
        private const val PREVIEW_SPEED_MAX = 5L * 1024L * 1024L

        /** 手动注入工厂（与仓库既有 ViewModel 同一套约定）。 */
        fun factory(application: BurnInApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = application.appContainer
                UpdateViewModel(
                    application = application,
                    checker = container.updateChecker,
                    downloader = container.updateDownloader,
                    snoozeStore = container.updateSnoozeStore,
                )
            }
        }
    }
}
