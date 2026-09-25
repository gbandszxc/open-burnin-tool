package com.github.gbandszxc.obt.domain.model

/**
 * 内置煲机预设目录。
 *
 * - [CLASSIC]：「标准四阶段 · 120 小时」四阶段方案。阶段时长与音量梯度逐项取自
 *   原版逆向结论，音源在原版基础上把内置音乐替换为合成音源（内置音频资产已移除，
 *   见 [CLASSIC_PHASES]）：
 *   舒筋 12h 白噪 1/5 → 活络 12h 粉噪 1/3 →
 *   习武 72h 粉噪恒定 7/15 → 打擂 24h 白噪↔粉噪每 30 分钟轮换 3/5，总 432000s。
 * - [quick]：快捷单阶段方案（本应用自加的便捷路径，非原版行为），音源可选、默认白噪。
 * - [custom]：自定义小时数，四阶段按原版比例（10/10/60/20）缩放。
 *   注意：原版自定义时长时阶段区间是硬编码的（短时长会直接落在「打擂」区间），
 *   这里改为按比例缩放以保留「先轻后重」的煲机曲线。
 */
object BurnPlans {

    /** 原版默认总时长：432000 秒 = 120 小时。 */
    const val CLASSIC_TOTAL_SECONDS: Long = 432_000L

    /** 打擂阶段音源轮换周期：1800 秒 = 30 分钟（沿原版 (剩余秒/1800)%2 判定）。 */
    const val ALTERNATE_EVERY_SECONDS: Long = 1_800L

    /**
     * 标准四阶段时长（秒）：12h / 12h / 72h / 24h。
     *
     * 阶段时长/音量沿原版逆向结论；音源为本版编排（内置音乐资产已移除）：
     * 舒筋→白噪、活络→粉噪、习武→粉噪恒定（不轮换，音量沿用原版习武的 7/15）、
     * 打擂→按剩余秒 (r/1800)%2 判定：偶数→白噪、奇数→粉噪（轮换位置与原版一致，
     * 仅把原版的音乐换成白噪；剩余秒语义见 [BurnSequencer]）。
     */
    val CLASSIC_PHASES: List<BurnPhase> = listOf(
        BurnPhase(0, "舒筋", 43_200L, SoundSource.WHITE_NOISE, 1.0 / 5.0),
        BurnPhase(1, "活络", 43_200L, SoundSource.PINK_NOISE, 1.0 / 3.0),
        // 习武：粉噪恒定（原版此阶段为内置音乐恒定，资产移除后替换为粉噪、音量不变）
        BurnPhase(2, "习武", 259_200L, SoundSource.PINK_NOISE, 7.0 / 15.0),
        // 打擂：白噪↔粉噪每 30 分钟轮换（沿原版轮换位置，(剩余秒/1800)%2 == 0 → 白噪）
        BurnPhase(
            index = 3,
            name = "打擂",
            durationSeconds = 86_400L,
            soundSource = SoundSource.WHITE_NOISE,
            volumeRatio = 3.0 / 5.0,
            alternateWith = SoundSource.PINK_NOISE,
            alternateEverySeconds = ALTERNATE_EVERY_SECONDS,
        ),
    )

    /** 原版标准方案。 */
    val CLASSIC: BurnPlan = BurnPlan("classic_120h", "标准四阶段 · 120 小时", CLASSIC_PHASES)

    /** 快捷预设小时数。 */
    val QUICK_HOURS: List<Int> = listOf(2, 8, 16, 24, 48, 72)

    /** 原版四阶段时长占比（用于自定义时长的等比缩放）：10% / 10% / 60% / 20%。 */
    private val CLASSIC_PROPORTIONS = doubleArrayOf(0.10, 0.10, 0.60, 0.20)

    /**
     * 快捷单阶段方案：全程同一音源、增益取该音源的默认增益，适合短时快速煲机。
     * 非原版行为，是本应用为常用时长（2/8/16/24/48/72 小时）提供的便捷预设。
     *
     * [sound] 默认白噪——白噪默认增益恰为原版第一阶段（舒筋）的温和音量 1/5，
     * 阶段名沿用既有「快速白噪」文案，因此缺省调用与历史行为完全一致。
     *
     * 本地音乐自定义音源：[localTrackId] 非空时本方案播放对应本地音轨
     * （Room local_tracks 表），此时 [sound] 被忽略、阶段音源强制为 [SoundSource.LOCAL_TRACK]，
     * 阶段音量取其默认增益（7/15，本地音源阶段的占位音量）；阶段名/方案显示名用 [soundLabel]
     * （如曲目名，空白时回退「本地音乐」），方案显示名追加曲目名便于历史辨识。
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
                name = label ?: "本地音乐",
                durationSeconds = hours * 3_600L,
                soundSource = SoundSource.LOCAL_TRACK,
                volumeRatio = SoundSource.LOCAL_TRACK.defaultGainRatio,
                localTrackId = localTrackId,
            )
        } else {
            val phaseName = if (sound == SoundSource.WHITE_NOISE) "快速白噪" else "快速${sound.displayName}"
            BurnPhase(
                index = 0,
                name = phaseName,
                durationSeconds = hours * 3_600L,
                soundSource = sound,
                volumeRatio = sound.defaultGainRatio,
            )
        }
        val planName = if (localTrackId != null) {
            "快速煲机 $hours 小时 · ${label ?: "本地音乐"}"
        } else {
            "快速煲机 $hours 小时"
        }
        return BurnPlan(id = "quick_${hours}h", name = planName, phases = listOf(phase))
    }

    /**
     * 自定义时长方案：四阶段按原版占比缩放到 [hours] 小时。
     *
     * 音源与音量保持各阶段原设定；打擂阶段轮换周期维持 30 分钟不变（听感节奏与总时长无关），
     * 但若缩放后阶段时长不足两个轮换周期，则取阶段时长的一半，保证至少能切换一次。
     */
    fun custom(hours: Int): BurnPlan {
        require(hours > 0) { "自定义小时数必须为正数：$hours" }
        val totalSeconds = hours * 3_600L
        // 前三个阶段按比例缩放，最后一个阶段取余量，保证各阶段之和严格等于总时长
        val durations = LongArray(CLASSIC_PHASES.size)
        for (index in 0 until CLASSIC_PHASES.size - 1) {
            durations[index] = (totalSeconds * CLASSIC_PROPORTIONS[index]).toLong().coerceAtLeast(1L)
        }
        durations[CLASSIC_PHASES.size - 1] =
            (totalSeconds - durations.dropLast(1).sum()).coerceAtLeast(1L)
        val phases = CLASSIC_PHASES.mapIndexed { index, source ->
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
        return BurnPlan(id = "custom_${hours}h", name = "自定义 $hours 小时", phases = phases)
    }

    /** 按预设小时数取方案：120 小时走原版方案，快捷时长走快捷单阶段方案（默认白噪），其余走等比缩放。 */
    fun forPresetHours(hours: Int): BurnPlan = when {
        hours * 3_600L == CLASSIC_TOTAL_SECONDS -> CLASSIC
        hours in QUICK_HOURS -> quick(hours)
        else -> custom(hours)
    }
}
