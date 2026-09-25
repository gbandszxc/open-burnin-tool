package com.github.gbandszxc.obt.domain.model

/**
 * 内置煲机预设目录。
 *
 * - [classic]：「标准四阶段 · 120 小时」四阶段方案（[CLASSIC] 为缺省形态）。
 *   阶段时长与音量梯度逐项取自原版逆向结论，音源在原版基础上把内置音乐替换为合成音源
 *   （内置音频资产已移除，见 [CLASSIC_PHASES]）：
 *   舒缓 12h 白噪 1/5 → 适应 12h 粉噪 1/3 →
 *   稳定 72h 粉噪恒定 7/15 → 轮换 24h 白噪↔粉噪每 30 分钟轮换 3/5，总 432000s。
 *   可选 [classic] 的稳定阶段单曲注入：传入歌单把稳定阶段替换为本地音乐阶段（时长不变）。
 * - [quick]：快捷单阶段方案（本应用自加的便捷路径，非原版行为），音源可选、默认白噪。
 * - [custom]：自定义小时数，四阶段按原版比例（舒缓 10 / 适应 10 / 稳定 60 / 轮换 20，
 *   按 [BurnPhase.stageId] 绑定而非列表位置）缩放，同样支持稳定阶段单曲注入。
 *   注意：原版自定义时长时阶段区间是硬编码的（短时长会直接落在「轮换」区间），
 *   这里改为按比例缩放以保留「先轻后重」的煲机曲线。
 */
object BurnPlans {

    /** 原版默认总时长：432000 秒 = 120 小时。 */
    const val CLASSIC_TOTAL_SECONDS: Long = 432_000L

    /** 轮换阶段音源轮换周期：1800 秒 = 30 分钟（沿原版 (剩余秒/1800)%2 判定）。 */
    const val ALTERNATE_EVERY_SECONDS: Long = 1_800L

    // 阶段身份（stageId）：方案编排（排序/音量覆盖/单曲注入）按身份定位，不随播放顺序改变
    private const val STAGE_GENTLE = 0
    private const val STAGE_ADAPT = 1
    private const val STAGE_STEADY = 2
    private const val STAGE_ALTERNATE = 3

    /**
     * 标准四阶段时长（秒）：12h / 12h / 72h / 24h。
     *
     * 阶段时长/音量沿原版逆向结论；音源为本版编排（内置音乐资产已移除）：
     * 舒缓→白噪、适应→粉噪、稳定→粉噪恒定（不轮换，音量沿用原版稳定阶段的 7/15）、
     * 轮换→按剩余秒 (r/1800)%2 判定：偶数→白噪、奇数→粉噪（轮换位置与原版一致，
     * 仅把原版的音乐换成白噪；剩余秒语义见 [BurnSequencer]）。
     */
    val CLASSIC_PHASES: List<BurnPhase> = listOf(
        BurnPhase(0, "gentle", 43_200L, SoundSource.WHITE_NOISE, 1.0 / 5.0, stageId = STAGE_GENTLE),
        BurnPhase(1, "adapt", 43_200L, SoundSource.PINK_NOISE, 1.0 / 3.0, stageId = STAGE_ADAPT),
        // 稳定：粉噪恒定（原版此阶段为内置音乐恒定，资产移除后替换为粉噪、音量不变）
        BurnPhase(2, "steady", 259_200L, SoundSource.PINK_NOISE, 7.0 / 15.0, stageId = STAGE_STEADY),
        // 轮换：白噪↔粉噪每 30 分钟轮换（沿原版轮换位置，(剩余秒/1800)%2 == 0 → 白噪）
        BurnPhase(
            index = 3,
            name = "alternate",
            durationSeconds = 86_400L,
            soundSource = SoundSource.WHITE_NOISE,
            volumeRatio = 3.0 / 5.0,
            alternateWith = SoundSource.PINK_NOISE,
            alternateEverySeconds = ALTERNATE_EVERY_SECONDS,
            stageId = STAGE_ALTERNATE,
        ),
    )

    /** 原版标准方案（缺省形态，稳定阶段为合成粉噪）。 */
    val CLASSIC: BurnPlan = BurnPlan("classic_120h", "标准四阶段 · 120 小时", CLASSIC_PHASES)

    /** 快捷预设小时数。 */
    val QUICK_HOURS: List<Int> = listOf(2, 8, 16, 24, 48, 72)

    /**
     * 原版四阶段时长占比，按阶段身份（[BurnPhase.stageId]）绑定而非列表位置：
     * 舒缓恒 10%、适应 10%、稳定 60%、轮换 20%。
     * 这样即便阶段被重排/注入，自定义时长等比缩放时 60% 时长始终归属稳定阶段（stageId=2）。
     */
    private val CLASSIC_PROPORTIONS_BY_STAGE: Map<Int, Double> = mapOf(
        STAGE_GENTLE to 0.10,
        STAGE_ADAPT to 0.10,
        STAGE_STEADY to 0.60,
        STAGE_ALTERNATE to 0.20,
    )

    /**
     * 快捷单阶段方案：全程同一音源、增益取该音源的默认增益，适合短时快速煲机。
     * 非原版行为，是本应用为常用时长（2/8/16/24/48/72 小时）提供的便捷预设。
     *
     * [sound] 默认白噪——白噪默认增益恰为原版第一阶段（舒缓）的温和音量 1/5。
     *
     * 本地音乐自定义音源：[localTrackId] 非空时本方案播放对应本地音轨
     * （Room local_tracks 表），此时 [sound] 被忽略、阶段音源强制为 [SoundSource.LOCAL_TRACK]，
     * 阶段音量取其默认增益（7/15，本地音源阶段的占位音量）；阶段名用 [soundLabel]
     * （如曲目名，空白时回退 "local_track" 标识）。
     *
     * 单阶段方案阶段身份固定为 0（唯一阶段，合法即可）。
     *
     * 注意：[BurnPlan.name] 与 [BurnPhase.name] 是日志/测试用的内部标识，
     * 不是用户可见文案；方案/阶段/音源的显示名由 UI 与通知层按应用语言经资源解析。
     */
    fun quick(
        hours: Int,
        sound: SoundSource = SoundSource.WHITE_NOISE,
        localTrackId: Long? = null,
        soundLabel: String? = null,
    ): BurnPlan {
        require(hours > 0) { "快捷预设小时数必须为正数：$hours" }
        val label = soundLabel?.trim()?.takeIf { it.isNotEmpty() }
        val phase = if (localTrackId != null) {
            BurnPhase(
                index = 0,
                name = label ?: "local_track",
                durationSeconds = hours * 3_600L,
                soundSource = SoundSource.LOCAL_TRACK,
                volumeRatio = SoundSource.LOCAL_TRACK.defaultGainRatio,
                localTrackId = localTrackId,
                stageId = 0,
            )
        } else {
            BurnPhase(
                index = 0,
                name = "quick_${sound.name}",
                durationSeconds = hours * 3_600L,
                soundSource = sound,
                volumeRatio = sound.defaultGainRatio,
                stageId = 0,
            )
        }
        return BurnPlan(id = "quick_${hours}h", name = "quick_${hours}h", phases = listOf(phase))
    }

    /**
     * 标准四阶段方案；[steadyTrackIds] 非空时做稳定阶段单曲注入——把 stageId=2 的阶段
     * 替换为本地音乐阶段：时长不变（72h）、[BurnPhase.soundSource] 强制
     * [SoundSource.LOCAL_TRACK]、音量取其默认增益（7/15）、歌单为去重后的非空正数 id 列表
     * （非法 id 抛 [IllegalArgumentException]）、清空轮换配置（稳定阶段本无）、
     * 阶段名用 "steady_music"。方案 id 保持 "classic_120h"（续播匹配口径不变）。
     * 空列表时与 [CLASSIC] 完全等价。
     */
    fun classic(steadyTrackIds: List<Long> = emptyList()): BurnPlan {
        if (steadyTrackIds.isEmpty()) return CLASSIC
        val trackIds = requireValidTrackIds(steadyTrackIds)
        val phases = CLASSIC_PHASES.map { phase ->
            if (phase.stageId == STAGE_STEADY) withSteadyMusic(phase, trackIds) else phase
        }
        return BurnPlan(id = CLASSIC.id, name = CLASSIC.name, phases = phases)
    }

    /**
     * 自定义时长方案：四阶段按「阶段身份对应占比」（见 [CLASSIC_PROPORTIONS_BY_STAGE]）
     * 缩放到 [hours] 小时；阶段列表仍取 [CLASSIC_PHASES] 顺序，列表末位阶段取余量，
     * 保证各阶段之和严格等于总时长。
     *
     * 音源与音量保持各阶段原设定；轮换阶段轮换周期维持 30 分钟不变（听感节奏与总时长无关），
     * 但若缩放后阶段时长不足两个轮换周期，则取阶段时长的一半，保证至少能切换一次。
     *
     * [steadyTrackIds] 非空时做稳定阶段单曲注入：注入发生在等比缩放之后，
     * 稳定阶段时长仍为 60% 档不变，其余同 [classic] 的注入语义。
     */
    fun custom(hours: Int, steadyTrackIds: List<Long> = emptyList()): BurnPlan {
        require(hours > 0) { "自定义小时数必须为正数：$hours" }
        val totalSeconds = hours * 3_600L
        // 列表末位阶段取余量，前面的阶段按各自 stageId 的占比缩放
        val headDurations = CLASSIC_PHASES.dropLast(1).map { phase ->
            val proportion = CLASSIC_PROPORTIONS_BY_STAGE.getValue(phase.stageId)
            (totalSeconds * proportion).toLong().coerceAtLeast(1L)
        }
        val durations = headDurations + (totalSeconds - headDurations.sum()).coerceAtLeast(1L)
        val scaled = CLASSIC_PHASES.mapIndexed { index, source ->
            val duration = durations[index]
            val period = if (source.alternateWith != null) {
                minOf(ALTERNATE_EVERY_SECONDS, duration / 2L).coerceAtLeast(1L)
            } else {
                null
            }
            source.copy(
                index = index,
                durationSeconds = duration,
                alternateEverySeconds = period,
            )
        }
        val phases = if (steadyTrackIds.isEmpty()) {
            scaled
        } else {
            val trackIds = requireValidTrackIds(steadyTrackIds)
            scaled.map { phase ->
                if (phase.stageId == STAGE_STEADY) withSteadyMusic(phase, trackIds) else phase
            }
        }
        return BurnPlan(id = "custom_${hours}h", name = "custom_${hours}h", phases = phases)
    }

    /** 按预设小时数取方案：120 小时走原版方案，快捷时长走快捷单阶段方案（默认白噪），其余走等比缩放。 */
    fun forPresetHours(hours: Int): BurnPlan = when {
        hours * 3_600L == CLASSIC_TOTAL_SECONDS -> CLASSIC
        hours in QUICK_HOURS -> quick(hours)
        else -> custom(hours)
    }

    /** 校验注入歌单：必须非空且 id 全为正数（非法抛 [IllegalArgumentException]），去重后保序。 */
    private fun requireValidTrackIds(trackIds: List<Long>): List<Long> {
        require(trackIds.isNotEmpty() && trackIds.all { it > 0L }) {
            "注入歌单的音轨 id 必须全为正数且列表非空：$trackIds"
        }
        return trackIds.distinct()
    }

    /** 把稳定阶段替换为本地音乐阶段：时长/身份不变，音源/音量/歌单按注入参数，清空轮换配置。 */
    private fun withSteadyMusic(steadyPhase: BurnPhase, trackIds: List<Long>): BurnPhase =
        steadyPhase.copy(
            name = "steady_music",
            soundSource = SoundSource.LOCAL_TRACK,
            volumeRatio = SoundSource.LOCAL_TRACK.defaultGainRatio,
            alternateWith = null,
            alternateEverySeconds = null,
            localTrackIds = trackIds,
        )
}
