package com.github.gbandszxc.obt.playback

import com.github.gbandszxc.obt.domain.model.SoundSource

/** 播放器运行阶段：空闲（无会话）/ 播放中 / 暂停。 */
enum class PlaybackStatus { IDLE, PLAYING, PAUSED }

/**
 * 对外（UI / 通知 / 服务）暴露的播放状态快照。
 *
 * 由 [PlaybackController] 每秒发布一次；剩余秒与是否暂停为派生值，
 * 保证「已煲 + 剩余 = 计划」恒成立，不双份存储。
 *
 * 展示名不进本状态：只携带结构化身份（[planId] / [phaseIndex] / [stageId] / [soundSource] /
 * [localTrackName]），方案/阶段/音源的显示文案由 UI 与通知层按当前应用语言
 * 经资源解析（见 com.github.gbandszxc.obt.ui.PlanDisplay），语言切换后无需等待
 * 下一次状态发布即可正确本地化。
 *
 * @property status 运行阶段。
 * @property sessionId 当前 Room 会话 id；空闲时为 0。
 * @property planId 方案标识（如 quick_2h / classic_120h）。
 * @property plannedSeconds 计划总时长（秒）。
 * @property completedSeconds 已煲秒数（单调递增）。
 * @property phaseIndex 当前所处阶段序号，即「阶段 i/N」中的播放位置序号 i（多阶段方案随进度变化，
 *   与 [com.github.gbandszxc.obt.domain.model.BurnPhase.index] 同口径）。
 * @property stageId 当前阶段的固定身份（0=舒缓 / 1=适应 / 2=稳定 / 3=轮换，与
 *   [com.github.gbandszxc.obt.domain.model.BurnPhase.stageId] 同口径）：阶段名展示按它经语言资源
 *   解析，不随播放顺序（重排/注入）改变；空闲态为 null。
 * @property soundSource 当前实际音源（含轮换阶段判定结果）；本地音乐阶段为
 *   [SoundSource.LOCAL_TRACK]；空闲/未知时为 null。
 * @property localTrackName 本地音乐音源阶段的当前曲目展示名（由 PlaybackController 解析缓存，
 *   歌单循环内随切歌更新）；非本地音乐阶段为 null。
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val sessionId: Long = 0L,
    val planId: String = "",
    val plannedSeconds: Long = 0L,
    val completedSeconds: Long = 0L,
    val phaseIndex: Int = 0,
    val stageId: Int? = null,
    val soundSource: SoundSource? = null,
    val localTrackName: String? = null,
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
