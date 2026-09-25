package com.github.gbandszxc.obt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BurnPlans] 内置预设测试：标准四阶段方案参数逐项对照
 * （总 432000s；阶段 43200/43200/259200/86400；
 * 音源 白噪/粉噪/粉噪恒定/白噪↔粉噪轮换（内置音乐资产移除后的本版编排，阶段时长与音量沿原版）；
 * 音量 1/5、1/3、7/15、3/5；打擂轮换 1800s）。
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

        val shu = phases[0]
        assertEquals(0, shu.index)
        assertEquals("舒筋", shu.name)
        assertEquals(43_200L, shu.durationSeconds)
        assertEquals(SoundSource.WHITE_NOISE, shu.soundSource)
        assertEquals(1.0 / 5.0, shu.volumeRatio, 1e-9)
        assertNull(shu.alternateWith)

        val huo = phases[1]
        assertEquals(1, huo.index)
        assertEquals("活络", huo.name)
        assertEquals(43_200L, huo.durationSeconds)
        assertEquals(SoundSource.PINK_NOISE, huo.soundSource)
        assertEquals(1.0 / 3.0, huo.volumeRatio, 1e-9)
        assertNull(huo.alternateWith)

        // 习武：粉噪恒定（原版此阶段为内置音乐恒定，资产移除后替换为粉噪、音量不变）
        val xi = phases[2]
        assertEquals(2, xi.index)
        assertEquals("习武", xi.name)
        assertEquals(259_200L, xi.durationSeconds)
        assertEquals(SoundSource.PINK_NOISE, xi.soundSource)
        assertEquals(7.0 / 15.0, xi.volumeRatio, 1e-9)
        assertNull(xi.alternateWith)
        assertNull(xi.alternateEverySeconds)

        // 打擂：白噪↔粉噪每 30 分钟轮换（沿原版轮换位置，偶数段基准音源白噪）
        val da = phases[3]
        assertEquals(3, da.index)
        assertEquals("打擂", da.name)
        assertEquals(86_400L, da.durationSeconds)
        assertEquals(SoundSource.WHITE_NOISE, da.soundSource)
        assertEquals(3.0 / 5.0, da.volumeRatio, 1e-9)
        assertEquals(SoundSource.PINK_NOISE, da.alternateWith)
        assertEquals(1_800L, da.alternateEverySeconds)
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
    fun `自定义方案轮换配置仅存在于打擂且周期不超过阶段时长的一半`() {
        val plan = BurnPlans.custom(1) // 打擂阶段仅 720 秒
        assertNull(plan.phases[2].alternateWith)
        val daLei = plan.phases[3]
        assertEquals(SoundSource.PINK_NOISE, daLei.alternateWith)
        assertTrue(daLei.alternateEverySeconds!! <= daLei.durationSeconds / 2L)
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

    // ---- 模型约束 ----

    @Test(expected = IllegalArgumentException::class)
    fun `阶段序号必须与列表位置一致`() {
        BurnPlan(
            id = "bad",
            name = "bad",
            phases = listOf(
                BurnPhase(1, "错位", 100L, SoundSource.WHITE_NOISE, 0.2),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `音量比例越界被拒绝`() {
        BurnPhase(0, "越界", 100L, SoundSource.WHITE_NOISE, 1.2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `轮换周期为空时配置被拒绝`() {
        BurnPhase(0, "坏轮换", 100L, SoundSource.WHITE_NOISE, 0.5, alternateWith = SoundSource.PINK_NOISE)
    }
}
