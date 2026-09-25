package com.github.gbandszxc.obt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BurnPlans] 内置预设测试：标准四阶段方案参数逐项对照
 * （总 432000s；阶段 43200/43200/259200/86400；
 * 音源 白噪/粉噪/粉噪恒定/白噪↔粉噪轮换（内置音乐资产移除后的本版编排，阶段时长与音量沿原版）；
 * 音量 1/5、1/3、7/15、3/5；轮换阶段轮换 1800s；阶段身份 stageId 0/1/2/3）。
 */
class BurnPlansTest {

    // ---- 原版方案 ----

    @Test
    fun `原版方案总时长为432000秒`() {
        assertEquals(432_000L, BurnPlans.CLASSIC.totalSeconds)
        assertEquals(432_000L, BurnPlans.CLASSIC_TOTAL_SECONDS)
    }

    @Test
    fun `原版方案四阶段参数与逆向结论一致`() {
        val phases = BurnPlans.CLASSIC.phases
        assertEquals(4, phases.size)

        val gentle = phases[0]
        assertEquals(0, gentle.index)
        assertEquals(0, gentle.stageId)
        assertEquals("gentle", gentle.name)
        assertEquals(43_200L, gentle.durationSeconds)
        assertEquals(SoundSource.WHITE_NOISE, gentle.soundSource)
        assertEquals(1.0 / 5.0, gentle.volumeRatio, 1e-9)
        assertNull(gentle.alternateWith)

        val adapt = phases[1]
        assertEquals(1, adapt.index)
        assertEquals(1, adapt.stageId)
        assertEquals("adapt", adapt.name)
        assertEquals(43_200L, adapt.durationSeconds)
        assertEquals(SoundSource.PINK_NOISE, adapt.soundSource)
        assertEquals(1.0 / 3.0, adapt.volumeRatio, 1e-9)
        assertNull(adapt.alternateWith)

        // 稳定：粉噪恒定（原版此阶段为内置音乐恒定，资产移除后替换为粉噪、音量不变）
        val steady = phases[2]
        assertEquals(2, steady.index)
        assertEquals(2, steady.stageId)
        assertEquals("steady", steady.name)
        assertEquals(259_200L, steady.durationSeconds)
        assertEquals(SoundSource.PINK_NOISE, steady.soundSource)
        assertEquals(7.0 / 15.0, steady.volumeRatio, 1e-9)
        assertNull(steady.alternateWith)
        assertNull(steady.alternateEverySeconds)

        // 轮换：白噪↔粉噪每 30 分钟轮换（沿原版轮换位置，偶数段基准音源白噪）
        val alternate = phases[3]
        assertEquals(3, alternate.index)
        assertEquals(3, alternate.stageId)
        assertEquals("alternate", alternate.name)
        assertEquals(86_400L, alternate.durationSeconds)
        assertEquals(SoundSource.WHITE_NOISE, alternate.soundSource)
        assertEquals(3.0 / 5.0, alternate.volumeRatio, 1e-9)
        assertEquals(SoundSource.PINK_NOISE, alternate.alternateWith)
        assertEquals(1_800L, alternate.alternateEverySeconds)
    }

    // ---- 快捷预设 ----

    @Test
    fun `快捷预设小时数为2_8_16_24_48_72`() {
        assertEquals(listOf(2, 8, 16, 24, 48, 72), BurnPlans.QUICK_HOURS)
    }

    @Test
    fun `快捷方案为单阶段白噪且总时长等于小时数`() {
        for (hours in BurnPlans.QUICK_HOURS) {
            val plan = BurnPlans.quick(hours)
            assertEquals("quick_${hours}h", plan.id)
            assertEquals(hours * 3_600L, plan.totalSeconds)
            assertEquals(1, plan.phases.size)
            assertEquals(SoundSource.WHITE_NOISE, plan.phases[0].soundSource)
            assertNull(plan.phases[0].alternateWith)
        }
    }

    @Test
    fun `快捷方案默认参数保持白噪与原温和音量`() {
        val plan = BurnPlans.quick(8)
        val phase = plan.phases[0]
        assertEquals("quick_8h", plan.id)
        // name 是内部标识（与 planId 同款，非用户文案；展示名由 UI 按资源解析）
        assertEquals("quick_8h", plan.name)
        assertEquals("quick_WHITE_NOISE", phase.name)
        assertEquals(SoundSource.WHITE_NOISE, phase.soundSource)
        // 白噪默认增益 0.2 恰为原快捷方案固定音量 1/5，缺省调用行为不变
        assertEquals(1.0 / 5.0, phase.volumeRatio, 1e-9)
        // 单阶段方案阶段身份固定为 0
        assertEquals(0, phase.stageId)
    }

    @Test
    fun `快捷方案可指定音源且增益取该音源默认值`() {
        val plan = BurnPlans.quick(8, SoundSource.PINK_NOISE)
        assertEquals("quick_8h", plan.id)
        assertEquals("quick_8h", plan.name)
        assertEquals(8 * 3_600L, plan.totalSeconds)
        assertEquals(1, plan.phases.size)
        val phase = plan.phases[0]
        assertEquals("quick_PINK_NOISE", phase.name)
        assertEquals(SoundSource.PINK_NOISE, phase.soundSource)
        assertEquals(SoundSource.PINK_NOISE.defaultGainRatio, phase.volumeRatio, 1e-9)
        assertNull(phase.alternateWith)
    }

    // ---- 自定义时长（四阶段等比缩放） ----

    @Test
    fun `自定义方案四阶段时长之和等于总时长`() {
        for (hours in listOf(1, 4, 24, 96)) {
            val plan = BurnPlans.custom(hours)
            assertEquals(hours * 3_600L, plan.totalSeconds)
            assertEquals(plan.totalSeconds, plan.phases.sumOf { it.durationSeconds })
        }
    }

    @Test
    fun `自定义方案保持原版阶段比例与音源音量`() {
        val plan = BurnPlans.custom(4) // 14400 秒 → 1440/1440/8640/2880
        val durations = plan.phases.map { it.durationSeconds }
        assertEquals(listOf(1_440L, 1_440L, 8_640L, 2_880L), durations)
        assertEquals(SoundSource.WHITE_NOISE, plan.phases[0].soundSource)
        assertEquals(SoundSource.PINK_NOISE, plan.phases[1].soundSource)
        assertEquals(SoundSource.PINK_NOISE, plan.phases[2].soundSource)
        assertEquals(3.0 / 5.0, plan.phases[3].volumeRatio, 1e-9)
    }

    @Test
    fun `自定义方案轮换配置仅存在于轮换阶段且周期不超过阶段时长的一半`() {
        val plan = BurnPlans.custom(1) // 轮换阶段仅 720 秒
        assertNull(plan.phases[2].alternateWith)
        val rotate = plan.phases[3]
        assertEquals(SoundSource.PINK_NOISE, rotate.alternateWith)
        assertTrue(rotate.alternateEverySeconds!! <= rotate.durationSeconds / 2L)
    }

    @Test
    fun `自定义方案planId与显示名跟随小时数`() {
        for (hours in listOf(24, 48, 120, 240)) {
            val plan = BurnPlans.custom(hours)
            assertEquals("custom_${hours}h", plan.id)
            // name 是内部标识（与 planId 同款，非用户文案；展示名由 UI 按资源解析）
            assertEquals("custom_${hours}h", plan.name)
        }
        // 同小时数构造等价：续播匹配（presetHours + plannedSeconds）依赖该确定性
        assertEquals(BurnPlans.custom(48), BurnPlans.custom(48))
    }

    @Test
    fun `自定义方案边界24与240小时四阶段齐全且总时长正确`() {
        for (hours in listOf(24, 240)) {
            val plan = BurnPlans.custom(hours)
            assertEquals(4, plan.phases.size)
            assertEquals(hours * 3_600L, plan.totalSeconds)
            assertEquals(plan.totalSeconds, plan.phases.sumOf { it.durationSeconds })
        }
    }

    // ---- 按小时数取方案 ----

    @Test
    fun `120小时返回原版方案`() {
        assertEquals(BurnPlans.CLASSIC, BurnPlans.forPresetHours(120))
    }

    @Test
    fun `快捷小时数返回快捷方案其余返回自定义方案`() {
        assertEquals(BurnPlans.quick(8), BurnPlans.forPresetHours(8))
        assertEquals(BurnPlans.custom(36), BurnPlans.forPresetHours(36))
    }

    // ---- 响度标记（系统音量模式） ----

    @Test
    fun `方案煲机标记系统音量模式且快捷方案保持播放器增益`() {
        // 方案煲机（classic/custom）响度经系统媒体音量表达；自由煲机（quick）维持播放器增益
        assertTrue(BurnPlans.CLASSIC.loudnessViaSystemVolume)
        assertTrue(BurnPlans.classic().loudnessViaSystemVolume)
        assertTrue(BurnPlans.classic(listOf(7L)).loudnessViaSystemVolume)
        assertTrue(BurnPlans.custom(24).loudnessViaSystemVolume)
        assertTrue(BurnPlans.custom(24, listOf(7L)).loudnessViaSystemVolume)
        assertFalse(BurnPlans.quick(8).loudnessViaSystemVolume)
        assertFalse(BurnPlans.quick(8, localTrackIds = listOf(7L)).loudnessViaSystemVolume)
    }

    @Test
    fun `编排派生后系统音量标记随方案保留`() {
        // withStageOrder / withStageGains 经 data class copy 派生：标记随对象保留，无需特判
        assertTrue(BurnPlans.CLASSIC.withStageOrder(listOf(3, 2, 1, 0)).loudnessViaSystemVolume)
        assertTrue(BurnPlans.CLASSIC.withStageGains(mapOf(2 to 0.5)).loudnessViaSystemVolume)
        assertFalse(BurnPlans.quick(2).withStageGains(mapOf(0 to 0.5)).loudnessViaSystemVolume)
    }

    // ---- 模型约束 ----

    @Test(expected = IllegalArgumentException::class)
    fun `阶段序号必须与列表位置一致`() {
        BurnPlan(
            id = "bad",
            name = "bad",
            phases = listOf(
                BurnPhase(1, "错位", 100L, SoundSource.WHITE_NOISE, 0.2, stageId = 0),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `音量比例越界被拒绝`() {
        BurnPhase(0, "越界", 100L, SoundSource.WHITE_NOISE, 1.2, stageId = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `轮换周期为空时配置被拒绝`() {
        BurnPhase(0, "坏轮换", 100L, SoundSource.WHITE_NOISE, 0.5, alternateWith = SoundSource.PINK_NOISE, stageId = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `阶段身份缺省占位不可直接成案`() {
        // stageId 构造默认 -1（历史构造点可编译），但 BurnPlan 层拒绝未显式指定身份的方案
        BurnPlan(
            id = "no_stage",
            name = "no_stage",
            phases = listOf(
                BurnPhase(0, "缺身份", 100L, SoundSource.WHITE_NOISE, 0.2),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `阶段身份同方案内重复被拒绝`() {
        BurnPlan(
            id = "dup_stage",
            name = "dup_stage",
            phases = listOf(
                BurnPhase(0, "甲", 100L, SoundSource.WHITE_NOISE, 0.2, stageId = 1),
                BurnPhase(1, "乙", 100L, SoundSource.PINK_NOISE, 0.2, stageId = 1),
            ),
        )
    }
}
