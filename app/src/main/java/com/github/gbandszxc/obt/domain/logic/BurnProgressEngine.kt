package com.github.gbandszxc.obt.domain.logic

/**
 * 煲机进度引擎的运行态。
 * 不含 ABANDONED：放弃是用户对会话的操作，由持久层状态表达，不属于计时逻辑。
 */
enum class EngineStatus { RUNNING, PAUSED, COMPLETED }

/**
 * 煲机进度引擎：纯逻辑，负责 tick 累加、剩余计算与完成判定。
 *
 * 取代原 App 的「Timer 333ms → 每 3 拍减 1 秒 + 墙钟漂移事后校正」实现：
 * 改为直接按单调时钟锚点推算已播秒数累加到 completedSeconds，
 * 持久化时只保存 completedSeconds（剩余秒 = plannedSeconds - completedSeconds）。
 *
 * 不依赖任何 Android 类型，可直接在 JVM 单元测试中运行。
 *
 * @property plannedSeconds 计划总时长（秒），必须为正数。
 * @param initialCompletedSeconds 续播时的已完成秒数（0 ≤ 值 ≤ plannedSeconds）。
 * @param initialStatus 初始运行态，默认直接开始计时；若已完成秒数已达计划值则强制为 [EngineStatus.COMPLETED]。
 */
class BurnProgressEngine(
    val plannedSeconds: Long,
    initialCompletedSeconds: Long = 0L,
    initialStatus: EngineStatus = EngineStatus.RUNNING,
) {
    init {
        require(plannedSeconds > 0) { "计划时长必须为正数：$plannedSeconds" }
        require(initialCompletedSeconds in 0..plannedSeconds) {
            "初始已完成秒数越界：$initialCompletedSeconds（计划 $plannedSeconds）"
        }
    }

    /** 已完成秒数，单调递增，封顶于 [plannedSeconds]。 */
    var completedSeconds: Long = initialCompletedSeconds
        private set

    /** 当前运行态。 */
    var status: EngineStatus =
        if (initialCompletedSeconds >= plannedSeconds) EngineStatus.COMPLETED else initialStatus
        private set

    /** 单调时钟锚点（elapsedRealtime 毫秒）；null 表示尚未建立锚点或暂停后待重建。 */
    private var anchorElapsedMillis: Long? = null

    /** 是否已完成（completedSeconds 达到 plannedSeconds）。 */
    val isCompleted: Boolean get() = status == EngineStatus.COMPLETED

    /** 剩余秒数（plannedSeconds - completedSeconds，恒非负）。 */
    val remainingSeconds: Long get() = plannedSeconds - completedSeconds

    /** 进度比例 [0.0, 1.0]。 */
    val progressFraction: Double get() = completedSeconds.toDouble() / plannedSeconds.toDouble()

    /**
     * 累加 [deltaSeconds] 秒。仅在 RUNNING 态生效；达到计划值即转入 COMPLETED。
     * 非正数增量直接忽略。返回最新状态。
     */
    fun tick(deltaSeconds: Long): EngineStatus {
        if (status != EngineStatus.RUNNING || deltaSeconds <= 0L) return status
        completedSeconds = (completedSeconds + deltaSeconds).coerceAtMost(plannedSeconds)
        if (completedSeconds >= plannedSeconds) status = EngineStatus.COMPLETED
        return status
    }

    /**
     * 用单调时钟（elapsedRealtime 毫秒）推进：首次调用建立锚点，之后每次调用
     * 按与上次的时间差折算秒数并 [tick]。时间回拨按 0 处理。
     * 返回最新状态。
     */
    fun advanceTo(nowElapsedRealtimeMillis: Long): EngineStatus {
        val anchor = anchorElapsedMillis
        anchorElapsedMillis = nowElapsedRealtimeMillis
        if (anchor != null) {
            val deltaMillis = (nowElapsedRealtimeMillis - anchor).coerceAtLeast(0L)
            tick(deltaMillis / 1_000L)
        }
        return status
    }

    /** 暂停：丢弃锚点（暂停期间流逝的时间不计入进度）。 */
    fun pause(): Boolean {
        if (status != EngineStatus.RUNNING) return false
        status = EngineStatus.PAUSED
        anchorElapsedMillis = null
        return true
    }

    /** 恢复：仅 PAUSED 态可回到 RUNNING；锚点由下一次 [advanceTo] 重建。 */
    fun resume(): Boolean {
        if (status != EngineStatus.PAUSED) return false
        status = EngineStatus.RUNNING
        return true
    }
}
