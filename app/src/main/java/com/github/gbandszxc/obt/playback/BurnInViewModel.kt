package com.github.gbandszxc.obt.playback

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.data.BurnInRepository
import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.DEFAULT_PLAN_STAGE_ORDER
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.data.SettingsRepository
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

    /** 本地音乐曲目（播放走 LOCAL_TRACK 音源 + 曲目文件，见 [BurnPlans.quick] 的 localTrackIds 参数）。 */
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
 * 煲机页未开始态的配置状态聚合：模式、方案卡、自定义输入、方案阶段编排配置
 * （顺序/响度覆盖/稳定阶段音乐）、自由音效、本地音轨列表、导入进度与可续播会话。
 * 全部由 [BurnInViewModel] 单向驱动，UI 只读并上抛事件。
 *
 * 派生属性（小时数解析/校验、生效稳定歌单）在此层收敛，保证 UI 与开始入口使用同一套口径；
 * 方案组装纯函数见 [buildStagePlan]。
 */
data class BurnInUiState(
    val mode: BurnMode = BurnMode.PLAN,
    val planCard: PlanCard = PlanCard.CLASSIC,

    /** 方案煲机「自定义四阶段」的总时长输入（纯数字字符串，空串表示未填）。 */
    val planCustomHoursInput: String = DEFAULT_PLAN_CUSTOM_HOURS.toString(),

    /**
     * 方案煲机四阶段播放顺序（stageId 的全排列，0=舒缓 / 1=适应 / 2=稳定 / 3=轮换），
     * 作用于经典与自定义方案。
     * 缺省 [com.github.gbandszxc.obt.data.DEFAULT_PLAN_STAGE_ORDER]（0 舒缓 → 1 适应 → 2 稳定 → 3 轮换）。
     * 单一数据源口径：本字段由设置流回流写入，见 [BurnInViewModel] 的 set 方法约定。
     */
    val stageOrder: List<Int> = DEFAULT_PLAN_STAGE_ORDER,

    /**
     * 四阶段响度覆盖（stageId → 比例，(0, 1]），作用于经典与自定义方案。
     * 空 map 表示未覆盖，UI 显示各阶段默认比例（1/5、1/3、7/15、3/5）。
     */
    val stageGains: Map<Int, Double> = emptyMap(),

    /** 稳定阶段（stageId = 2）音乐替换开关；仅与非空生效歌单组合才真正生效（见 [steadyMusicEffective]）。 */
    val steadyMusicEnabled: Boolean = false,

    /** 稳定阶段替换曲目的有序 id 列表（勾选顺序 = 播放顺序），可能包含已失效 id，用前先看派生字段。 */
    val steadyTrackIds: List<Long> = emptyList(),

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

    /**
     * 稳定阶段音乐实际生效的歌单：开关开启时取 [steadyTrackIds] 与 [tracks] 的交集
     * （保序、去重，剔除已失效 id），关闭时恒为空列表。
     */
    val effectiveSteadyTrackIds: List<Long>
        get() = if (!steadyMusicEnabled) {
            emptyList()
        } else {
            steadyTrackIds.filter { id -> tracks.any { it.id == id } }.distinct()
        }

    /** 稳定阶段音乐替换是否生效：开关开启且生效歌单非空；否则回退粉噪恒定。 */
    val steadyMusicEffective: Boolean
        get() = steadyMusicEnabled && effectiveSteadyTrackIds.isNotEmpty()

    companion object {

        /** 方案自定义小时数合法范围。 */
        val PLAN_CUSTOM_HOURS_RANGE: IntRange = 8..240

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

        /**
         * 按当前阶段编排配置组装方案煲机方案（经典与自定义共用），纯函数、JVM 可测。
         *
         * 组装链固定：先经工厂做稳定阶段音乐注入（[BurnPlans.classic] / [BurnPlans.custom]，
         * [steadyEnabled] 且 [steadyTrackIds] 非空才传入歌单，否则空列表 = 缺省粉噪恒定），
         * 再 [BurnPlan.withStageOrder] 重排播放顺序，最后 [BurnPlan.withStageGains] 按身份覆盖响度
         * （后两步均按 stageId 定位，与注入互不干扰）。
         *
         * @param hours 自定义方案总时长（[classic] = false 时必须非空且 > 0；经典方案忽略）。
         * @param classic true 走经典标准四阶段（id 固定 classic_120h），false 走自定义四阶段。
         * @param stageOrder stageId 全排列（缺省传 0,1,2,3 即与现行为等价）；非法排列由
         *   [BurnPlan.withStageOrder] 拒绝（调用方入口已按 [com.github.gbandszxc.obt.data.parseStageOrder]
         *   同口径校验）。
         * @param stageGains 响度覆盖（值域 (0, 1]，越界由 [BurnPlan.withStageGains] 拒绝）。
         */
        fun buildStagePlan(
            hours: Int?,
            classic: Boolean,
            stageOrder: List<Int>,
            stageGains: Map<Int, Double>,
            steadyEnabled: Boolean,
            steadyTrackIds: List<Long>,
        ): BurnPlan {
            // 稳定阶段音乐：开关开且歌单非空才注入；否则空列表保持粉噪恒定（缺省形态）
            val effectiveIds = if (steadyEnabled && steadyTrackIds.isNotEmpty()) steadyTrackIds else emptyList()
            val base = if (classic) {
                BurnPlans.classic(effectiveIds)
            } else {
                BurnPlans.custom(requireNotNull(hours) { "自定义方案组装必须提供小时数" }, effectiveIds)
            }
            return base.withStageOrder(stageOrder).withStageGains(stageGains)
        }
    }
}

/**
 * 播放层暴露给 UI 的 ViewModel：[PlaybackController] 的薄封装 + 煲机页配置状态中枢。
 *
 * 控制器持有全部播放逻辑与状态（Application 单例，存活于 Activity 之外），
 * 播放控制只做转发，保证旋转/重建期间会话状态不丢；本类额外收敛煲机页全部未开始态配置：
 * 模式切换、方案卡与自定义小时输入、自由音效选择、本地音轨列表与导入/删除、
 * 选中方案的可续播会话查询（选中方案变化或播放回到空闲时自动刷新），
 * 以及方案阶段编排配置（阶段顺序/响度覆盖/稳定阶段音乐，持久化于
 * [SettingsRepository]，经 DataStore 流回流驱动 uiState）。
 *
 * 单一数据源口径：阶段编排配置的 set 方法只持久化到 DataStore，**不直接改 uiState**；
 * 状态统一由对应 flow 回流写入（init 内收集器），避免「先改状态再持久化失败」的双写竞态。
 */
class BurnInViewModel(
    private val controller: PlaybackController,
    private val burnInRepository: BurnInRepository,
    private val trackRepository: TrackRepository,
    private val settingsRepository: SettingsRepository,
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
        // 本地音轨列表随 Room 流刷新；当前选中的本地曲目被移除时回退默认白噪，避免悬挂选择；
        // 同时修剪持久化的稳定阶段歌单（用户删除曲目后自动清理失效 id，见 pruneSteadyTrackIds）
        viewModelScope.launch {
            trackRepository.tracks.collect { tracks ->
                pruneSteadyTrackIds(tracks)
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
        // 方案阶段编排配置（顺序/响度覆盖/稳定阶段音乐开关/歌单）随 DataStore 流回流：
        // 单一数据源 —— set 方法只持久化，不直接改 uiState，状态统一由本收集器写入。
        // 四字段先合成快照再整体去重，避免多字段连续变更时对 uiState 的重复覆盖。
        viewModelScope.launch {
            combine(
                settingsRepository.planStageOrder,
                settingsRepository.planStageGains,
                settingsRepository.planSteadyMusicEnabled,
                settingsRepository.planSteadyTrackIds,
            ) { order, gains, enabled, trackIds ->
                StageConfigSnapshot(order, gains, enabled, trackIds)
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    _uiState.update {
                        it.copy(
                            stageOrder = snapshot.stageOrder,
                            stageGains = snapshot.stageGains,
                            steadyMusicEnabled = snapshot.steadyMusicEnabled,
                            steadyTrackIds = snapshot.steadyTrackIds,
                        )
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
        setPlanCustomHours(
            (base + delta).coerceIn(
                BurnInUiState.PLAN_CUSTOM_HOURS_RANGE.first,
                BurnInUiState.PLAN_CUSTOM_HOURS_RANGE.last,
            ).toString(),
        )
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
    // 方案阶段编排配置（持久化口径见类 KDoc：只写 DataStore，状态由流回流）
    // ------------------------------------------------------------------

    /**
     * 保存方案四阶段播放顺序：[order] 必须是 stageId 0..3 的全排列（与
     * [com.github.gbandszxc.obt.data.parseStageOrder] 同口径），否则整体忽略不持久化。
     */
    fun setStageOrder(order: List<Int>) {
        if (order.size != DEFAULT_PLAN_STAGE_ORDER.size || order.toSet() != DEFAULT_PLAN_STAGE_ORDER.toSet()) return
        viewModelScope.launch { settingsRepository.setPlanStageOrder(order) }
    }

    /**
     * 覆盖/清除单个阶段的响度比例：[ratio] 非 null 须落在 (0, 1]（含有限性校验），
     * null 表示清除该阶段覆盖（从覆盖表去掉后持久化剩余项）；stageId 越界或取值非法
     * 或结果与当前一致时忽略，不产生多余写入。
     */
    fun setStageGain(stageId: Int, ratio: Double?) {
        val next = _uiState.value.stageGains.toMutableMap()
        if (ratio == null) {
            if (next.remove(stageId) == null) return
        } else {
            if (stageId !in DEFAULT_PLAN_STAGE_ORDER) return
            if (!ratio.isFinite() || ratio <= 0.0 || ratio > 1.0) return
            if (next[stageId] == ratio) return
            next[stageId] = ratio
        }
        viewModelScope.launch { settingsRepository.setPlanStageGains(next) }
    }

    /** 保存稳定阶段音乐替换开关（配合非空生效歌单才真正生效，见 [BurnInUiState.steadyMusicEffective]）。 */
    fun setSteadyMusicEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPlanSteadyMusicEnabled(enabled) }
    }

    /**
     * 切换稳定阶段歌单里某首曲目的勾选状态：已含则移除，未含则追加到末尾
     * （勾选顺序 = 播放顺序），持久化后由设置流回流。
     */
    fun toggleSteadyTrack(trackId: Long) {
        val current = _uiState.value.steadyTrackIds
        val next = if (current.contains(trackId)) current - trackId else current + trackId
        viewModelScope.launch { settingsRepository.setPlanSteadyTrackIds(next) }
    }

    /**
     * 修剪稳定阶段歌单中已失效（被用户删除）的曲目 id 并持久化，只发一次：
     * 与当前 uiState 比对无变化不写。修剪后歌单为空且开关仍开启时，一并持久化
     * 关闭开关（回退粉噪恒定），其余配置记忆语义不受影响。
     */
    private suspend fun pruneSteadyTrackIds(tracks: List<LocalTrack>) {
        val state = _uiState.value
        val kept = state.steadyTrackIds.filter { id -> tracks.any { it.id == id } }
        if (kept.size == state.steadyTrackIds.size) return
        settingsRepository.setPlanSteadyTrackIds(kept)
        if (state.steadyMusicEnabled && kept.isEmpty()) {
            settingsRepository.setPlanSteadyMusicEnabled(false)
        }
    }

    // ------------------------------------------------------------------
    // 开始入口
    // ------------------------------------------------------------------

    /**
     * 开始方案煲机：[plan] 为选中卡对应的方案；[resumeFromSeconds] 非空且大于 0 时
     * 从该已完成秒数续播（来自 [BurnInUiState.resumableSession] 的查询结果），
     * 否则全新开始。[startPaused] = true 时以暂停态起步（历史记录续播，见
     * [PlaybackController.start]）。播放中调用会被控制器忽略（幂等）。
     */
    fun startPlan(plan: BurnPlan, resumeFromSeconds: Long? = null, startPaused: Boolean = false) {
        val from = resumeFromSeconds?.takeIf { it > 0L } ?: 0L
        controller.start(plan, from, startPaused)
    }

    /**
     * 开始经典标准四阶段方案：按当前阶段编排配置（顺序/响度覆盖/稳定阶段音乐）
     * 经 [BurnInUiState.buildStagePlan] 组装后走 [startPlan]。
     *
     * 续播语义：[resumeFromSeconds] 非空且大于 0 时从该已完成秒数续播；续播同样按
     * **当前配置**重建方案（编排配置变更后续播沿用新顺序/响度/歌单，记忆语义）。
     * 方案 id 不随编排变化（classic_120h），续播会话匹配口径不受影响。
     */
    fun startClassicPlan(resumeFromSeconds: Long? = null) {
        startPlan(buildCurrentStagePlan(classic = true), resumeFromSeconds)
    }

    /**
     * 开始自定义四阶段方案：[hours] 为总时长（调用方传入已校验的合法值，8-240），
     * 其余组装与续播语义同 [startClassicPlan]。
     */
    fun startCustomPlan(hours: Int, resumeFromSeconds: Long? = null) {
        startPlan(buildCurrentStagePlan(classic = false, hours = hours), resumeFromSeconds)
    }

    /** 读当前 uiState 组装本帧的方案煲机方案（稳定阶段歌单取派生的失效剔除后交集）。 */
    private fun buildCurrentStagePlan(classic: Boolean, hours: Int? = null): BurnPlan {
        val state = _uiState.value
        return BurnInUiState.buildStagePlan(
            hours = hours,
            classic = classic,
            stageOrder = state.stageOrder,
            stageGains = state.stageGains,
            steadyEnabled = state.steadyMusicEnabled,
            steadyTrackIds = state.effectiveSteadyTrackIds,
        )
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
                localTrackIds = listOf(selection.track.id),
                soundLabel = selection.track.displayName,
            )
        }
        controller.start(plan)
    }

    /**
     * 从历史记录续播一次进行中/已暂停的会话（历史页「继续」入口）：
     * 由会话行重建方案（[planForSession]，按当前编排配置组装，与方案卡续播的
     * 记忆语义一致），以会话行已完成秒数为起点、**暂停态**起步——切回煲机页后
     * 表盘定格为暂停，由煲机页既有「继续/结束」按钮恢复或终止。
     * 控制器非空闲（已有会话）或方案不可重建（本地音乐自由煲机等）时忽略。
     */
    fun resumeSessionFromHistory(session: BurnInSession) {
        if (controller.state.value.status != PlaybackStatus.IDLE) {
            Log.i(TAG, "已有会话进行中，忽略历史记录续播")
            return
        }
        val state = _uiState.value
        val plan = planForSession(
            session = session,
            stageOrder = state.stageOrder,
            stageGains = state.stageGains,
            steadyEnabled = state.steadyMusicEnabled,
            steadyTrackIds = state.effectiveSteadyTrackIds,
        )
        if (plan == null) {
            Log.w(TAG, "历史会话方案不可重建（本地音乐自由煲机或音源编号未知），忽略续播：id=${session.id}")
            return
        }
        if (plan.totalSeconds != session.plannedSeconds) {
            Log.w(
                TAG,
                "历史会话重建方案总时长与会话行不一致" +
                    "（重建=${plan.totalSeconds}s，行=${session.plannedSeconds}s），以重建方案为准：id=${session.id}",
            )
        }
        startPlan(plan, session.completedSeconds, startPaused = true)
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
            val track = importTrackInternal(uri)
            if (track != null) {
                // 导入即选中，衔接「导入 → 出现在下拉 → 可直接开始」链路
                setFreeSound(FreeSoundSelection.LocalMusic(track))
                _importEvents.tryEmit(TrackImportResult.Success(track.displayName))
            } else {
                _importEvents.tryEmit(TrackImportResult.Failure)
            }
        }
    }

    /**
     * 从内容 [Uri] 导入本地音乐并加入稳定阶段歌单：复用 [importTrack] 的导入链路与
     * importing 态，但成功后**不改**自由煲机音效，而是把新曲目 id 追加到稳定阶段歌单末尾
     * （勾选顺序 = 播放顺序）并持久化；失败照旧发出 [TrackImportResult.Failure]。
     * 导入进行中重复调用忽略。
     */
    fun importSteadyTrack(uri: Uri) {
        if (_uiState.value.importing) return
        viewModelScope.launch {
            val track = importTrackInternal(uri)
            if (track != null) {
                settingsRepository.setPlanSteadyTrackIds(_uiState.value.steadyTrackIds + track.id)
                _importEvents.tryEmit(TrackImportResult.Success(track.displayName))
            } else {
                _importEvents.tryEmit(TrackImportResult.Failure)
            }
        }
    }

    /** 导入公共链路：置 importing 态、仓库层拷贝（异常兜底为 null）、复位 importing。 */
    private suspend fun importTrackInternal(uri: Uri): LocalTrack? {
        _uiState.update { it.copy(importing = true) }
        val track = runCatching { trackRepository.importFromUri(uri) }.getOrNull()
        _uiState.update { it.copy(importing = false) }
        return track
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

        private const val TAG = "BurnInViewModel"

        /**
         * 判断历史会话行是否可从记录页续播（进行中/已暂停行「继续」按钮的显示口径）：
         * 方案煲机会话（soundSourceId 为 null）总能按当前编排配置重建方案；自由煲机会话
         * 仅内置合成音源可由音源编号还原——本地音乐（只存曲目名快照、未存歌单 id，无法
         * 重建歌单）与未知编号均不可重建。纯函数、JVM 可测，UI 只做布尔渲染。
         */
        fun sessionResumableFromHistory(session: BurnInSession): Boolean {
            if (session.soundSourceId == null) return true
            val sound = SoundSource.fromLegacySoundId(session.soundSourceId)
            return sound != null && sound != SoundSource.LOCAL_TRACK
        }

        /**
         * 由历史会话行重建可续播方案；不可重建返回 null（口径同 [sessionResumableFromHistory]）。
         *
         * - 方案煲机会话（soundSourceId 为 null）：计划时长为经典规模（120h；同规模的
         *   custom_120h 阶段时长与之等价，沿仓库层续播匹配的「规模一致即等价」口径）走
         *   经典方案，其余走自定义等比方案；两者均经 [BurnInUiState.buildStagePlan] 按
         *   **当前编排配置**（顺序/响度覆盖/稳定阶段音乐）重组——与方案卡续播的记忆语义一致；
         * - 自由煲机会话：按音源编号还原内置合成音源后走 [BurnPlans.quick]，时长取会话行的
         *   预设小时数；本地音乐/未知编号返回 null。
         *
         * 纯函数、JVM 可测；重建结果的 [BurnPlan.totalSeconds] 与会话行
         * [BurnInSession.plannedSeconds] 不一致时由调用方记日志并以重建方案为准。
         */
        fun planForSession(
            session: BurnInSession,
            stageOrder: List<Int>,
            stageGains: Map<Int, Double>,
            steadyEnabled: Boolean,
            steadyTrackIds: List<Long>,
        ): BurnPlan? {
            val sound = SoundSource.fromLegacySoundId(session.soundSourceId)
            if (session.soundSourceId != null) {
                // 自由煲机会话：本地音乐（歌单 id 未落库）或未知编号无法重建
                if (sound == null || sound == SoundSource.LOCAL_TRACK) return null
                return BurnPlans.quick(session.presetHours, sound)
            }
            return if (session.plannedSeconds == BurnPlans.CLASSIC_TOTAL_SECONDS) {
                BurnInUiState.buildStagePlan(
                    hours = null,
                    classic = true,
                    stageOrder = stageOrder,
                    stageGains = stageGains,
                    steadyEnabled = steadyEnabled,
                    steadyTrackIds = steadyTrackIds,
                )
            } else {
                // 自定义方案时长取会话行预设小时数（非正数为脏数据，放弃重建）
                val hours = session.presetHours.takeIf { it > 0 } ?: return null
                BurnInUiState.buildStagePlan(
                    hours = hours,
                    classic = false,
                    stageOrder = stageOrder,
                    stageGains = stageGains,
                    steadyEnabled = steadyEnabled,
                    steadyTrackIds = steadyTrackIds,
                )
            }
        }

        /** 手动注入工厂（项目不使用 Hilt，见 [com.github.gbandszxc.obt.data.AppContainer]）。 */
        fun factory(application: BurnInApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = application.appContainer
                BurnInViewModel(
                    controller = container.playbackController,
                    burnInRepository = container.burnInRepository,
                    trackRepository = container.trackRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
        }
    }
}

/**
 * 方案阶段编排配置四元组快照：combine 中转值，整体去重（数据类相等比较）后
 * 统一回流写入 [BurnInUiState] 的对应字段，避免多字段连续变更时的重复覆盖。
 */
private data class StageConfigSnapshot(
    val stageOrder: List<Int>,
    val stageGains: Map<Int, Double>,
    val steadyMusicEnabled: Boolean,
    val steadyTrackIds: List<Long>,
)
