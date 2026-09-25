package com.github.gbandszxc.obt.playback

/** 播放器运行阶段：空闲（无会话）/ 播放中 / 暂停。 */
enum class PlaybackStatus { IDLE, PLAYING, PAUSED }

/**
 * 对外（UI / 通知 / 服务）暴露的播放状态快照。
 *
 * 由 [PlaybackController] 每秒发布一次；剩余秒与是否暂停为派生值，
 * 保证「已煲 + 剩余 = 计划」恒成立，不双份存储。
 *
 * @property status 运行阶段。
 * @property sessionId 当前 Room 会话 id；空闲时为 0。
 * @property planId 方案标识（如 quick_2h / classic_120h）。
 * @property planName 方案显示名。
 * @property plannedSeconds 计划总时长（秒）。
 * @property completedSeconds 已煲秒数（单调递增）。
 * @property phaseName 当前所处煲机阶段名（多阶段方案随进度变化）。
 * @property soundSourceName 当前音源中文名（如「粉红噪音」「白噪音」，含打擂轮换结果）；
 *   本地音乐自定义音源阶段为本曲目的 displayName（由 PlaybackController 切阶段时解析缓存）。
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val sessionId: Long = 0L,
    val planId: String = "",
    val planName: String = "",
    val plannedSeconds: Long = 0L,
    val completedSeconds: Long = 0L,
    val phaseName: String = "",
    val soundSourceName: String = "",
) {
    /** 剩余秒数 = 计划 - 已煲，恒非负。 */
    val remainingSeconds: Long
        get() = (plannedSeconds - completedSeconds).coerceAtLeast(0L)

    /** 是否处于暂停态。 */
    val isPaused: Boolean get() = status == PlaybackStatus.PAUSED

    /** 进度比例 [0.0, 1.0]；空闲态（计划为 0）返回 0。 */
    val progressFraction: Double
        get() = if (plannedSeconds <= 0L) 0.0 else completedSeconds.toDouble() / plannedSeconds.toDouble()

    companion object {
        /** 空闲态单例。 */
        val IDLE: PlaybackState = PlaybackState()
    }
}
