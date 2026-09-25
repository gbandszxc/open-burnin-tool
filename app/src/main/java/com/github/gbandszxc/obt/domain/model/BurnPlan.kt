package com.github.gbandszxc.obt.domain.model

/**
 * 煲机方案中的一个阶段。
 *
 * 标准四阶段为「舒筋 12h 白噪 → 活络 12h 粉噪 → 习武 72h 粉噪 → 打擂 24h 白噪↔粉噪轮换」，
 * 每阶段固定音量（播放器级增益，不劫持系统媒体音量）。
 *
 * @property index 阶段序号，从 0 开始，随 [BurnPlan.phases] 顺序递增。
 * @property name 阶段内部标识（舒筋/活络/习武/打擂等，仅日志/测试用）；
 *   用户可见的阶段名由展示层按阶段序号经资源解析（见 ui/PlanDisplay.kt）。
 * @property durationSeconds 阶段时长（秒）。
 * @property soundSource 基准音源。
 * @property volumeRatio 音量比例，取值 [0.0, 1.0]，如标准四阶段为 1/5、1/3、7/15、3/5。
 * @property alternateWith 轮换音源；为 null 表示本阶段不轮换。
 * @property alternateEverySeconds 轮换周期（秒）。标准方案的打擂阶段为 1800（30 分钟），
 *   按「阶段内已播秒数 / 周期」的奇偶切换基准音源与 [alternateWith]。
 * @property localTrackId 本地音乐音轨 id（Room local_tracks 表，见
 *   com.github.gbandszxc.obt.data.LocalTrack）；**非空时本阶段循环播放该本地音乐文件，
 *   音量走 [volumeRatio]，[soundSource]/[alternateWith]/[alternateEverySeconds] 不参与本阶段**。
 *   默认 null 保持既有行为（历史调用点零改动）。
 */
data class BurnPhase(
    val index: Int,
    val name: String,
    val durationSeconds: Long,
    val soundSource: SoundSource,
    val volumeRatio: Double,
    val alternateWith: SoundSource? = null,
    val alternateEverySeconds: Long? = null,
    val localTrackId: Long? = null,
) {
    init {
        require(durationSeconds > 0) { "阶段时长必须为正数：$durationSeconds" }
        require(volumeRatio in 0.0..1.0) { "音量比例越界：$volumeRatio" }
        require((alternateWith == null) == (alternateEverySeconds == null)) {
            "轮换音源与轮换周期必须同时配置或同时为空"
        }
        if (alternateEverySeconds != null) {
            require(alternateEverySeconds > 0) { "轮换周期必须为正数：$alternateEverySeconds" }
        }
        require(localTrackId == null || localTrackId > 0L) {
            "本地音轨 id 必须为正数：$localTrackId"
        }
    }

    /** 阶段内已播 [elapsedInPhaseSeconds] 秒时应当使用的音源。 */
    fun soundSourceAt(elapsedInPhaseSeconds: Long): SoundSource {
        val period = alternateEverySeconds
        val alternate = alternateWith
        if (period == null || alternate == null) return soundSource
        val switchParity = (elapsedInPhaseSeconds.coerceAtLeast(0L) / period) % 2L
        return if (switchParity == 1L) alternate else soundSource
    }
}

/**
 * 煲机方案：阶段按顺序衔接，总时长为各阶段之和。
 *
 * 原版默认方案总时长 432000 秒（120 小时）。
 */
data class BurnPlan(
    val id: String,

    /** 方案内部标识（仅日志/测试用）；用户可见的方案名由展示层按 [id] 经资源解析。 */
    val name: String,
    val phases: List<BurnPhase>,
) {
    init {
        require(phases.isNotEmpty()) { "煲机方案至少需要一个阶段" }
        require(phases.withIndex().all { (position, phase) -> phase.index == position }) {
            "阶段序号必须与列表位置一致（从 0 连续递增）"
        }
    }

    /** 总时长（秒）。 */
    val totalSeconds: Long = phases.sumOf { it.durationSeconds }
}
