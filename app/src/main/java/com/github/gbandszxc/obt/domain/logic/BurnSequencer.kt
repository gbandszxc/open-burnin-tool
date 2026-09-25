package com.github.gbandszxc.obt.domain.logic

import com.github.gbandszxc.obt.domain.model.BurnPhase
import com.github.gbandszxc.obt.domain.model.BurnPlan
import com.github.gbandszxc.obt.domain.model.SoundSource

/**
 * 煲机方案在某一时刻的定位结果。
 *
 * @property phaseIndex 阶段序号（0 起）。
 * @property phase 所处阶段。
 * @property elapsedInPhaseSeconds 阶段内已播秒数。
 * @property phaseRemainingSeconds 阶段内剩余秒数。
 * @property planRemainingSeconds 整个方案剩余秒数。
 * @property soundSource 当前应使用的音源（已含打擂阶段 30 分钟轮换判定）。
 * @property volumeRatio 当前阶段的固定音量比例。
 */
data class PhasePosition(
    val phaseIndex: Int,
    val phase: BurnPhase,
    val elapsedInPhaseSeconds: Long,
    val phaseRemainingSeconds: Long,
    val planRemainingSeconds: Long,
    val soundSource: SoundSource,
    val volumeRatio: Double,
)

/**
 * 阶段时序器：给定方案与已播秒数，定位所处阶段并判定当前音源。
 *
 * 阶段定位与原 App 实现等价：按总剩余秒划分四阶段，
 * 与本实现的方案内已播秒数前缀和等价。
 *
 * 轮换语义（沿原版）：对配置了 [BurnPhase.alternateWith] 的阶段
 * （标准方案中即「打擂」），按「**阶段内剩余秒** / 轮换周期」的奇偶决定音源——
 * 偶数段用基准音源、奇数段用轮换音源（原版按 (剩余秒/1800)%2 判定，偶数段基准音源；
 * 原版每秒重评估、音源变化时停旧起新，本实现同语义）。
 * 注意：[BurnPhase.soundSourceAt] 的「已播秒数」正序轮换仅为模型层默认实现；
 * 播放链路以本时序器的剩余秒语义为准，与原版逐秒对齐（含打擂开场基准音源先播 1 秒的边界行为：
 * 阶段剩余 86400s 恰为周期的整数倍，其后即进入 1800s 的粉噪/白噪交替段）。
 *
 * 纯逻辑，可直接在 JVM 单元测试中运行。
 */
class BurnSequencer(private val plan: BurnPlan) {

    /** 各阶段起始秒数（前缀和），与 [BurnPlan.phases] 一一对应。 */
    private val phaseStartSeconds: List<Long> = buildList {
        var cursor = 0L
        for (phase in plan.phases) {
            add(cursor)
            cursor += phase.durationSeconds
        }
    }

    /** 定位 [elapsedSeconds]（方案内已播秒数）处的阶段与音源。
     *  越界输入按边界收敛：负值取 0，超出总时长取方案末尾。 */
    fun positionAt(elapsedSeconds: Long): PhasePosition {
        val elapsed = elapsedSeconds.coerceIn(0L, plan.totalSeconds)
        val index = phaseStartSeconds.indexOfLast { it <= elapsed }.coerceAtLeast(0)
        val phase = plan.phases[index]
        val elapsedInPhase = elapsed - phaseStartSeconds[index]
        return PhasePosition(
            phaseIndex = index,
            phase = phase,
            elapsedInPhaseSeconds = elapsedInPhase,
            phaseRemainingSeconds = phase.durationSeconds - elapsedInPhase,
            planRemainingSeconds = plan.totalSeconds - elapsed,
            soundSource = soundSourceAt(phase, elapsedInPhase),
            volumeRatio = phase.volumeRatio,
        )
    }

    /** 阶段内 [elapsedInPhaseSeconds] 处应使用的音源：无轮换配置取基准音源；
     *  有轮换配置（打擂）按「阶段内剩余秒 / 周期」奇偶切换，偶数段基准、奇数段轮换。 */
    private fun soundSourceAt(phase: BurnPhase, elapsedInPhaseSeconds: Long): SoundSource {
        val period = phase.alternateEverySeconds ?: return phase.soundSource
        val alternate = phase.alternateWith ?: return phase.soundSource
        val remaining = (phase.durationSeconds - elapsedInPhaseSeconds).coerceIn(0L, phase.durationSeconds)
        val parity = (remaining / period) % 2L
        return if (parity == 0L) phase.soundSource else alternate
    }
}
