package com.github.gbandszxc.obt.playback

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.data.BurnInRepository
import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.data.TrackRepository
import com.github.gbandszxc.obt.domain.model.BurnPlan
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 煲机页的两条路线：
 * - [PLAN] 方案煲机：按指定方案（经典标准四阶段 / 自定义四阶段）依次走各音效阶段并记录进度；
 * - [FREE] 自由煲机：自选音效（内置 7 合成音源或本地音乐）与时长，单阶段任意煲。
 *
 * 展示名不进枚举：UI 按枚举项经资源解析（stringResource），随应用语言切换。
 */
enum class BurnMode {
    PLAN,
    FREE,
}

/** 方案煲机模式下的两张方案卡（展示名由 UI 按枚举项经资源解析）。 */
enum class PlanCard {
    CLASSIC,
    CUSTOM,
}

/**
 * 自由煲机的音效选择：内置 [SoundSource.catalog] 7 合成音源之一，或一条本地音乐曲目。
 * 开始煲机时由 ViewModel 映射为对应方案参数；展示名由 UI 解析（内置音源走
 * [SoundSource.nameRes] 资源，本地音乐直接显示曲目名）。
 */
sealed interface FreeSoundSelection {

    /** 内置音源（如白噪音/粉红噪音）。 */
    data class Builtin(val sound: SoundSource) : FreeSoundSelection

    /** 本地音乐曲目（播放走 LOCAL_TRACK 音源 + 曲目文件，见 [BurnPlans.quick] 的 localTrackId 参数）。 */
    data class LocalMusic(val track: LocalTrack) : FreeSoundSelection

    companion object {

        /** 默认选择：白噪音（与快捷预设的历史行为一致）。 */
        val DEFAULT: FreeSoundSelection = Builtin(SoundSource.WHITE_NOISE)
    }
}

/** 本地音乐导入结果事件（UI 据此弹一次行内提示）。 */
sealed interface TrackImportResult {

    /** 导入成功，携带曲目展示名。 */
    data class Success(val trackName: String) : TrackImportResult

    /** 导入失败（文件打不开/拷贝中断等，仓库层已兜底不抛出）。 */
    data object Failure : TrackImportResult
}

/**
 * 煲机页未开始态的配置状态聚合：模式、方案卡、自定义输入、自由音效、本地音轨列表、
 * 导入进度与可续播会话。全部由 [BurnInViewModel] 单向驱动，UI 只读并上抛事件。
 *
 * 派生属性（小时数解析/校验）在此层收敛，保证 UI 与开始入口使用同一套校验口径。
 */
data class BurnInUiState(
    val mode: BurnMode = BurnMode.PLAN,
    val planCard: PlanCard = PlanCard.CLASSIC,

    /** 方案煲机「自定义四阶段」的总时长输入（纯数字字符串，空串表示未填）。 */
    val planCustomHoursInput: String = DEFAULT_PLAN_CUSTOM_HOURS.toString(),

    /** 当前选中方案的可续播会话（RUNNING/PAUSED 且已有进度）；无则 null。 */
    val resumableSession: BurnInSession? = null,

    /** 自由煲机选中的音效。 */
    val freeSound: FreeSoundSelection = FreeSoundSelection.DEFAULT,

    /** 已导入的本地音轨（按加入时间倒序）。 */
    val tracks: List<LocalTrack> = emptyList(),

    /** 本地音乐导入进行中（导入项显示进度态并禁用重复导入）。 */
    val importing: Boolean = false,

    /** 自由煲机选中的预设小时数（QUICK_HOURS 之一）。 */
    val freePresetHours: Int = DEFAULT_FREE_HOURS,

    /** 自由煲机自定义小时输入（空串表示未填，未填时用预设）。 */
    val freeCustomHoursInput: String = "",
) {

    /** 方案自定义小时数（合法范围 [PLAN_CUSTOM_HOURS_RANGE]），空/非法/越界返回 null。 */
    val planCustomHours: Int?
        get() = planCustomHoursInput.trim().toIntOrNull()?.takeIf { it in PLAN_CUSTOM_HOURS_RANGE }

    /**
     * 当前选中方案对应的 planId（如 classic_120h / custom_48h），供续播查询。
     * 自定义输入非法时返回 null（此时不查询续播）。
     */
    val selectedPlanId: String?
        get() = when (planCard) {
            PlanCard.CLASSIC -> BurnPlans.CLASSIC.id
            PlanCard.CUSTOM -> planCustomHours?.let { "custom_${it}h" }
        }

    /** 自由煲机自定义小时（合法范围 [FREE_CUSTOM_HOURS_RANGE]），空/非法返回 null。 */
    val freeCustomHours: Int?
        get() = freeCustomHoursInput.trim().toIntOrNull()?.takeIf { it in FREE_CUSTOM_HOURS_RANGE }

    /** 自由煲机实际生效的小时数：合法的自定义输入优先，否则用预设。 */
    val effectiveFreeHours: Int
        get() = freeCustomHours ?: freePresetHours

    /** 自定义输入已填但非法：禁用开始并提示。 */
    val freeCustomInputError: Boolean
        get() = freeCustomHoursInput.isNotBlank() && freeCustomHours == null

    /** 自由煲机是否可开始（自定义输入合法或未填）。 */
    val canStartFree: Boolean get() = !freeCustomInputError

    companion object {

        /** 方案自定义小时数合法范围。 */
        val PLAN_CUSTOM_HOURS_RANGE: IntRange = 24..240

        /** 方案自定义小时数默认值。 */
        const val DEFAULT_PLAN_CUSTOM_HOURS = 48

        /** 方案小时步进量（±12，与 10/10/60/20 的整时缩放保持整齐）。 */
        const val PLAN_CUSTOM_HOURS_STEP = 12

        /** 自由煲机自定义小时合法范围。 */
        val FREE_CUSTOM_HOURS_RANGE: IntRange = 1..999

        /** 自由煲机默认预设小时数。 */
        const val DEFAULT_FREE_HOURS = 8

        /** 输入框允许的最大数字位数（999 上限即 3 位）。 */
        const val MAX_INPUT_DIGITS = 3
    }
}

/**
 * 播放层暴露给 UI 的 ViewModel：[PlaybackController] 的薄封装 + 煲机页配置状态中枢。
 *
 * 控制器持有全部播放逻辑与状态（Application 单例，存活于 Activity 之外），
 * 播放控制只做转发，保证旋转/重建期间会话状态不丢；本类额外收敛煲机页全部未开始态配置：
 * 模式切换、方案卡与自定义小时输入、自由音效选择、本地音轨列表与导入/删除、
 * 选中方案的可续播会话查询（选中方案变化或播放回到空闲时自动刷新）。
 */
class BurnInViewModel(
    private val controller: PlaybackController,
    private val burnInRepository: BurnInRepository,
    private val trackRepository: TrackRepository,
) : ViewModel() {

    /** 播放状态（已煲/剩余/暂停等），每秒刷新。 */
    val playbackState: StateFlow<PlaybackState> = controller.state

    private val _uiState = MutableStateFlow(BurnInUiState())

    /** 煲机页未开始态配置（见 [BurnInUiState]）。 */
    val uiState: StateFlow<BurnInUiState> = _uiState.asStateFlow()

    private val _importEvents = MutableSharedFlow<TrackImportResult>(extraBufferCapacity = 1)

    /** 本地音乐导入结果事件（每次导入完成/失败发一条，UI 弹行内提示）。 */
    val importEvents: SharedFlow<TrackImportResult> = _importEvents.asSharedFlow()

    init {
        // 本地音轨列表随 Room 流刷新；当前选中的本地曲目被移除时回退默认白噪，避免悬挂选择
        viewModelScope.launch {
            trackRepository.tracks.collect { tracks ->
                _uiState.update { state ->
                    val sound = state.freeSound
                    val safeSound = if (
                        sound is FreeSoundSelection.LocalMusic &&
                        tracks.none { it.id == sound.track.id }
                    ) {
                        FreeSoundSelection.DEFAULT
                    } else {
                        sound
                    }
                    state.copy(tracks = tracks, freeSound = safeSound)
                }
            }
        }
        // 可续播会话查询：选中方案（planId）或播放回到空闲（会话结束/完成）时刷新；
        // collectLatest 保证快速切换方案时旧查询被取消、只落最后一次结果。
        // 必须对 (planId, idle) 二元组整体去重：若只按 planId 去重，会话结束回到 IDLE 时
        // planId 未变，发射会被吞掉，导致过期「上次进度」永远留在方案卡上。
        viewModelScope.launch {
            combine(
                uiState.map { it.selectedPlanId }.distinctUntilChanged(),
                playbackState.map { it.status == PlaybackStatus.IDLE }.distinctUntilChanged(),
            ) { planId, idle -> planId to idle }
                .distinctUntilChanged() // (planId, 是否空闲) 任一变化都触发重查；播放中每秒 status 刷新被抑制
                .collectLatest { (planId, _) ->
                    val session = planId?.let { burnInRepository.latestResumableSession(it) }
                    _uiState.update { it.copy(resumableSession = session) }
                }
        }
    }

    // ------------------------------------------------------------------
    // 配置态变更
    // ------------------------------------------------------------------

    /** 切换煲机路线（方案煲机 / 自由煲机）。 */
    fun setMode(mode: BurnMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    /** 选中方案卡（经典标准 / 自定义四阶段）。 */
    fun setPlanCard(card: PlanCard) {
        _uiState.update { it.copy(planCard = card) }
    }

    /** 更新方案自定义总时长输入（只保留数字，最多 [BurnInUiState.MAX_INPUT_DIGITS] 位）。 */
    fun setPlanCustomHours(input: String) {
        _uiState.update {
            it.copy(planCustomHoursInput = input.filter(Char::isDigit).take(BurnInUiState.MAX_INPUT_DIGITS))
        }
    }

    /** 方案自定义总时长步进：[direction] > 0 增加，否则减少，步长 [BurnInUiState.PLAN_CUSTOM_HOURS_STEP]。 */
    fun stepPlanCustomHours(direction: Int) {
        val state = _uiState.value
        // 输入非法时步进回到最近的合法值，给用户一个可继续操作的起点
        val base = state.planCustomHours
            ?: state.planCustomHoursInput.trim().toIntOrNull()?.coerceIn(
                BurnInUiState.PLAN_CUSTOM_HOURS_RANGE.first,
                BurnInUiState.PLAN_CUSTOM_HOURS_RANGE.last,
            )
            ?: BurnInUiState.DEFAULT_PLAN_CUSTOM_HOURS
        val delta = if (direction > 0) {
            BurnInUiState.PLAN_CUSTOM_HOURS_STEP
        } else {
            -BurnInUiState.PLAN_CUSTOM_HOURS_STEP
        }
        setPlanCustomHours((base + delta).coerceIn(24, 240).toString())
    }

    /** 自由煲机选中预设时长（同时清空自定义输入，两者互斥）。 */
    fun setFreePresetHours(hours: Int) {
        _uiState.update { it.copy(freePresetHours = hours, freeCustomHoursInput = "") }
    }

    /** 更新自由煲机自定义小时输入（只保留数字，最多 [BurnInUiState.MAX_INPUT_DIGITS] 位）。 */
    fun setFreeCustomHours(input: String) {
        _uiState.update {
            it.copy(freeCustomHoursInput = input.filter(Char::isDigit).take(BurnInUiState.MAX_INPUT_DIGITS))
        }
    }

    /** 自由煲机选中音效（内置音源或本地曲目）。 */
    fun setFreeSound(selection: FreeSoundSelection) {
        _uiState.update { it.copy(freeSound = selection) }
    }

    // ------------------------------------------------------------------
    // 开始入口
    // ------------------------------------------------------------------

    /**
     * 开始方案煲机：[plan] 为选中卡对应的方案；[resumeFromSeconds] 非空且大于 0 时
     * 从该已完成秒数续播（来自 [BurnInUiState.resumableSession] 的查询结果），
     * 否则全新开始。播放中调用会被控制器忽略（幂等）。
     */
    fun startPlan(plan: BurnPlan, resumeFromSeconds: Long? = null) {
        if (resumeFromSeconds != null && resumeFromSeconds > 0L) {
            controller.start(plan, resumeFromSeconds)
        } else {
            controller.start(plan)
        }
    }

    /** 开始自由煲机：按当前音效选择与生效时长组装单阶段方案（[BurnPlans.quick]）。 */
    fun startFree() {
        val state = _uiState.value
        if (!state.canStartFree) return
        val hours = state.effectiveFreeHours
        val plan = when (val selection = state.freeSound) {
            is FreeSoundSelection.Builtin -> BurnPlans.quick(hours, selection.sound)
            is FreeSoundSelection.LocalMusic -> BurnPlans.quick(
                hours,
                localTrackId = selection.track.id,
                soundLabel = selection.track.displayName,
            )
        }
        controller.start(plan)
    }

    // ------------------------------------------------------------------
    // 本地音乐管理
    // ------------------------------------------------------------------

    /**
     * 从内容 [Uri] 导入本地音乐（SAF 返回的只读 uri，仓库层拷贝进应用私有目录）。
     * 成功后自动选中新曲目并发出 [TrackImportResult.Success]；失败发出 [TrackImportResult.Failure]，
     * 不影响既有状态。导入进行中重复调用忽略。
     */
    fun importTrack(uri: Uri) {
        if (_uiState.value.importing) return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true) }
            val track = runCatching { trackRepository.importFromUri(uri) }.getOrNull()
            _uiState.update { it.copy(importing = false) }
            if (track != null) {
                // 导入即选中，衔接「导入 → 出现在下拉 → 可直接开始」链路
                setFreeSound(FreeSoundSelection.LocalMusic(track))
                _importEvents.tryEmit(TrackImportResult.Success(track.displayName))
            } else {
                _importEvents.tryEmit(TrackImportResult.Failure)
            }
        }
    }

    /** 移除本地音乐（删除私有目录文件 + 删除 Room 记录，仓库层兜底不抛出）。 */
    fun deleteTrack(track: LocalTrack) {
        viewModelScope.launch { trackRepository.delete(track) }
    }

    // ------------------------------------------------------------------
    // 播放控制转发
    // ------------------------------------------------------------------

    /** 暂停。 */
    fun pause() = controller.pause()

    /** 继续。 */
    fun resume() = controller.resume()

    /** 结束会话（放弃）。 */
    fun stop() = controller.stop()

    companion object {

        /** 手动注入工厂（项目不使用 Hilt，见 [com.github.gbandszxc.obt.data.AppContainer]）。 */
        fun factory(application: BurnInApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = application.appContainer
                BurnInViewModel(
                    controller = container.playbackController,
                    burnInRepository = container.burnInRepository,
                    trackRepository = container.trackRepository,
                )
            }
        }
    }
}
