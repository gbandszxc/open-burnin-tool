package com.github.gbandszxc.obt.domain.model

import com.github.gbandszxc.obt.domain.logic.BurnSequencer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 方案编排（阶段身份定位）测试：
 * - [BurnPlan.withStageOrder] 按 stageId 全排列重排：index 重编、属性随身份走、时序器按新序定位；
 * - [BurnPlan.withStageGains] 按身份覆盖音量：缺省保留、越界拒绝、未知身份忽略；
 * - [BurnPlans.classic] / [BurnPlans.custom] 稳定阶段（stageId=2）单曲注入：
 *   LOCAL_TRACK、7/15、时长不变（等比缩放后仍 60% 档）、无轮换；
 * - 自定义时长比例按身份绑定：60% 时长恒归属稳定阶段，不随播放顺序漂移。
 */
class BurnStageOrderTest {

    // ---- withStageOrder：按身份重排 ----

    @Test
    fun `重排3210后index连续且各阶段属性随身份走`() {
        val reordered = BurnPlans.CLASSIC.withStageOrder(listOf(3, 2, 1, 0))
        assertEquals(listOf(0, 1, 2, 3), reordered.phases.map { it.index })
        // 顺序倒转：身份随阶段原样保留
        assertEquals(listOf(3, 2, 1, 0), reordered.phases.map { it.stageId })
        assertEquals(listOf("alternate", "steady", "adapt", "gentle"), reordered.phases.map { it.name })
        // 时长/音量/轮换配置随身份走，播放顺序不改变属性
        assertEquals(listOf(86_400L, 259_200L, 43_200L, 43_200L), reordered.phases.map { it.durationSeconds })
        assertEquals(
            listOf(3.0 / 5.0, 7.0 / 15.0, 1.0 / 3.0, 1.0 / 5.0),
            reordered.phases.map { it.volumeRatio },
        )
        assertEquals(SoundSource.WHITE_NOISE, reordered.phases[0].soundSource)
        assertEquals(SoundSource.PINK_NOISE, reordered.phases[0].alternateWith)
        assertEquals(1_800L, reordered.phases[0].alternateEverySeconds)
        assertNull(reordered.phases[1].alternateWith)
        // 排序只改顺序：总时长不变，且为不可变新方案（原方案不受影响）
        assertEquals(432_000L, reordered.totalSeconds)
        assertEquals(listOf(0, 1, 2, 3), BurnPlans.CLASSIC.phases.map { it.stageId })
    }

    @Test
    fun `重排后时序器按新顺序定位且轮换判定随阶段走`() {
        val sequencer = BurnSequencer(BurnPlans.CLASSIC.withStageOrder(listOf(3, 2, 1, 0)))
        // 第 0 秒落在「轮换」阶段（stageId=3）：白噪基准、3/5 音量，剩余秒 86400=48×1800 偶段
        val atZero = sequencer.positionAt(0L)
        assertEquals(0, atZero.phaseIndex)
        assertEquals(3, atZero.phase.stageId)
        assertEquals(SoundSource.WHITE_NOISE, atZero.soundSource)
        assertEquals(3.0 / 5.0, atZero.volumeRatio, 1e-9)
        // 86400 秒切换到「稳定」阶段（stageId=2）：粉噪恒定 7/15
        val atSteady = sequencer.positionAt(86_400L)
        assertEquals(1, atSteady.phaseIndex)
        assertEquals(2, atSteady.phase.stageId)
        assertEquals(SoundSource.PINK_NOISE, atSteady.soundSource)
        assertEquals(7.0 / 15.0, atSteady.volumeRatio, 1e-9)
        // 方案末尾回到「舒缓」阶段（stageId=0）
        val atEnd = sequencer.positionAt(431_999L)
        assertEquals(3, atEnd.phaseIndex)
        assertEquals(0, atEnd.phase.stageId)
        assertEquals(SoundSource.WHITE_NOISE, atEnd.soundSource)
        assertEquals(1.0 / 5.0, atEnd.volumeRatio, 1e-9)
    }

    @Test
    fun `重排后按原顺序复原与原方案等价`() {
        val reordered = BurnPlans.CLASSIC.withStageOrder(listOf(3, 2, 1, 0))
        assertEquals(BurnPlans.CLASSIC, reordered.withStageOrder(listOf(0, 1, 2, 3)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `重排缺项被拒绝`() {
        BurnPlans.CLASSIC.withStageOrder(listOf(0, 1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `重排身份重复被拒绝`() {
        BurnPlans.CLASSIC.withStageOrder(listOf(0, 0, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `重排未知身份被拒绝`() {
        BurnPlans.CLASSIC.withStageOrder(listOf(0, 1, 2, 9))
    }

    // ---- withStageGains：按身份覆盖音量 ----

    @Test
    fun `音量覆盖指定身份且缺省保留`() {
        val plan = BurnPlans.CLASSIC.withStageGains(mapOf(2 to 0.5, 0 to 0.1))
        val byStage = plan.phases.associateBy { it.stageId }
        assertEquals(0.1, byStage.getValue(0).volumeRatio, 1e-9)
        assertEquals(1.0 / 3.0, byStage.getValue(1).volumeRatio, 1e-9) // 缺省保留
        assertEquals(0.5, byStage.getValue(2).volumeRatio, 1e-9)
        assertEquals(3.0 / 5.0, byStage.getValue(3).volumeRatio, 1e-9) // 缺省保留
        // 覆盖只动音量：其余属性与原方案一致
        assertEquals(BurnPlans.CLASSIC.phases.map { it.durationSeconds }, plan.phases.map { it.durationSeconds })
        assertEquals(432_000L, plan.totalSeconds)
    }

    @Test
    fun `音量覆盖未知身份被宽容忽略`() {
        assertEquals(BurnPlans.CLASSIC, BurnPlans.CLASSIC.withStageGains(mapOf(9 to 0.5)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `音量覆盖零比例被拒绝`() {
        BurnPlans.CLASSIC.withStageGains(mapOf(0 to 0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `音量覆盖越界比例被拒绝`() {
        BurnPlans.CLASSIC.withStageGains(mapOf(0 to 1.2))
    }

    // ---- 稳定阶段单曲注入：classic ----

    @Test
    fun `classic注入稳定阶段单曲后属性替换且其余阶段不变`() {
        val plan = BurnPlans.classic(listOf(11L, 5L, 5L, 9L)) // 重复 id 去重保序
        assertEquals(4, plan.phases.size)
        assertEquals(432_000L, plan.totalSeconds) // 时长不变
        assertEquals("classic_120h", plan.id) // 方案 id 保持（续播匹配口径不变）

        val steady = plan.phases.first { it.stageId == 2 }
        assertEquals(2, steady.index) // 位置不变
        assertEquals("steady_music", steady.name)
        assertEquals(SoundSource.LOCAL_TRACK, steady.soundSource)
        assertEquals(SoundSource.LOCAL_TRACK.defaultGainRatio, steady.volumeRatio, 1e-9)
        assertEquals(259_200L, steady.durationSeconds)
        assertEquals(listOf(11L, 5L, 9L), steady.localTrackIds)
        assertNull(steady.localTrackId) // 单音轨字段留待播放层单元收口
        assertNull(steady.alternateWith)
        assertNull(steady.alternateEverySeconds)

        // 其余阶段与原版逐项一致
        val others = plan.phases.filter { it.stageId != 2 }
        val classicOthers = BurnPlans.CLASSIC_PHASES.filter { it.stageId != 2 }
        assertEquals(classicOthers, others)
    }

    @Test
    fun `classic空歌单与原版方案完全等价`() {
        assertEquals(BurnPlans.CLASSIC, BurnPlans.classic())
        assertEquals(BurnPlans.CLASSIC, BurnPlans.classic(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `classic注入非法音轨id被拒绝`() {
        BurnPlans.classic(listOf(5L, 0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `classic注入负数音轨id被拒绝`() {
        BurnPlans.classic(listOf(-1L))
    }

    // ---- 稳定阶段单曲注入：custom ----

    @Test
    fun `custom注入发生在等比缩放之后稳定阶段时长保持六成档`() {
        val plan = BurnPlans.custom(4, listOf(7L))
        assertEquals(14_400L, plan.totalSeconds)
        val byStage = plan.phases.associateBy { it.stageId }
        val steady = byStage.getValue(2)
        assertEquals(8_640L, steady.durationSeconds) // 60% 档（14400 × 0.6），注入不缩水
        assertEquals(SoundSource.LOCAL_TRACK, steady.soundSource)
        assertEquals(SoundSource.LOCAL_TRACK.defaultGainRatio, steady.volumeRatio, 1e-9)
        assertEquals(listOf(7L), steady.localTrackIds)
        assertEquals("steady_music", steady.name)
        assertNull(steady.alternateWith)
        assertNull(steady.alternateEverySeconds)
        // 其余阶段仍为等比缩放结果
        assertEquals(1_440L, byStage.getValue(0).durationSeconds)
        assertEquals(1_440L, byStage.getValue(1).durationSeconds)
        assertEquals(2_880L, byStage.getValue(3).durationSeconds)
        assertEquals(3.0 / 5.0, byStage.getValue(3).volumeRatio, 1e-9)
    }

    @Test
    fun `custom空歌单与既有行为完全一致`() {
        assertEquals(BurnPlans.custom(48), BurnPlans.custom(48, emptyList()))
        assertEquals(BurnPlans.custom(4), BurnPlans.custom(4, emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `custom注入非法音轨id被拒绝`() {
        BurnPlans.custom(4, listOf(0L))
    }

    // ---- 比例按身份绑定 ----

    @Test
    fun `自定义时长六成档恒归属稳定阶段身份`() {
        // 按身份查找而非按位置断言：60% 时长始终归属 stageId=2
        for (hours in listOf(1, 4, 24)) {
            val plan = BurnPlans.custom(hours)
            val steady = plan.phases.first { it.stageId == 2 }
            assertEquals((hours * 3_600L * 0.6).toLong(), steady.durationSeconds)
        }
        // 排序后时长仍随身份走：轮换阶段排到首位，稳定阶段的 72h 不变
        val reordered = BurnPlans.CLASSIC.withStageOrder(listOf(3, 2, 1, 0))
        val steady = reordered.phases.first { it.stageId == 2 }
        assertEquals(259_200L, steady.durationSeconds)
        assertEquals(3, reordered.phases[0].stageId)
    }
}
