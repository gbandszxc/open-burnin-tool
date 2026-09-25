package com.github.gbandszxc.obt.playback

import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 煲机页配置状态 [BurnInUiState] 的派生逻辑测试：
 * 输入校验（方案 24-240 / 自由 1-999）、planId 推导（续播查询口径）、
 * 开始可用性判定与音效选择文案。纯 JVM 断言，不依赖 Android 框架。
 */
class BurnInUiStateTest {

    private fun track(id: Long, name: String = "曲目A.flac") = LocalTrack(
        id = id,
        displayName = name,
        fileName = "uuid.audio",
        addedAt = 0L,
    )

    // ---- 方案自定义小时数（24-240） ----

    @Test
    fun `方案自定义小时合法值解析`() {
        assertEquals(24, BurnInUiState(planCustomHoursInput = "24").planCustomHours)
        assertEquals(48, BurnInUiState(planCustomHoursInput = "48").planCustomHours)
        assertEquals(240, BurnInUiState(planCustomHoursInput = "240").planCustomHours)
    }

    @Test
    fun `方案自定义小时非法或越界返回null`() {
        assertNull(BurnInUiState(planCustomHoursInput = "").planCustomHours)
        assertNull(BurnInUiState(planCustomHoursInput = "abc").planCustomHours)
        assertNull(BurnInUiState(planCustomHoursInput = "23").planCustomHours)
        assertNull(BurnInUiState(planCustomHoursInput = "241").planCustomHours)
    }

    @Test
    fun `选中方案的planId推导与续播查询口径一致`() {
        val classic = BurnInUiState(planCard = PlanCard.CLASSIC)
        assertEquals(BurnPlans.CLASSIC.id, classic.selectedPlanId)

        val custom = BurnInUiState(planCard = PlanCard.CUSTOM, planCustomHoursInput = "48")
        assertEquals("custom_48h", custom.selectedPlanId)

        // 自定义输入非法时无 planId，调用方应跳过续播查询
        assertNull(BurnInUiState(planCard = PlanCard.CUSTOM, planCustomHoursInput = "12").selectedPlanId)
    }

    // ---- 自由煲机小时数（1-999，自定义优先于预设） ----

    @Test
    fun `自由自定义小时合法值解析`() {
        assertEquals(1, BurnInUiState(freeCustomHoursInput = "1").freeCustomHours)
        assertEquals(999, BurnInUiState(freeCustomHoursInput = "999").freeCustomHours)
    }

    @Test
    fun `自由自定义小时非法或越界返回null`() {
        assertNull(BurnInUiState(freeCustomHoursInput = "").freeCustomHours)
        assertNull(BurnInUiState(freeCustomHoursInput = "0").freeCustomHours)
        assertNull(BurnInUiState(freeCustomHoursInput = "1000").freeCustomHours)
        assertNull(BurnInUiState(freeCustomHoursInput = "3x").freeCustomHours)
    }

    @Test
    fun `生效小时数自定义优先于预设`() {
        val withCustom = BurnInUiState(freePresetHours = 8, freeCustomHoursInput = "36")
        assertEquals(36, withCustom.effectiveFreeHours)

        val withoutCustom = BurnInUiState(freePresetHours = 8, freeCustomHoursInput = "")
        assertEquals(8, withoutCustom.effectiveFreeHours)

        // 非法自定义不生效，回退预设
        val invalidCustom = BurnInUiState(freePresetHours = 16, freeCustomHoursInput = "0")
        assertEquals(16, invalidCustom.effectiveFreeHours)
    }

    @Test
    fun `自定义输入错误态与开始可用性一致`() {
        val blank = BurnInUiState(freeCustomHoursInput = "")
        assertFalse(blank.freeCustomInputError)
        assertTrue(blank.canStartFree)

        val valid = BurnInUiState(freeCustomHoursInput = "36")
        assertFalse(valid.freeCustomInputError)
        assertTrue(valid.canStartFree)

        val invalid = BurnInUiState(freeCustomHoursInput = "0")
        assertTrue(invalid.freeCustomInputError)
        assertFalse(invalid.canStartFree)
    }

    // ---- 默认值与音效选择文案 ----

    @Test
    fun `默认状态为方案模式标准四阶段白噪音`() {
        val state = BurnInUiState()
        assertEquals(BurnMode.PLAN, state.mode)
        assertEquals(PlanCard.CLASSIC, state.planCard)
        assertEquals(BurnPlans.CLASSIC.id, state.selectedPlanId)
        assertEquals(SoundSource.WHITE_NOISE, (state.freeSound as FreeSoundSelection.Builtin).sound)
        assertEquals(BurnInUiState.DEFAULT_FREE_HOURS, state.effectiveFreeHours)
        assertNull(state.resumableSession)
        assertFalse(state.importing)
    }

    @Test
    fun `音效选择文案取音源中文名或曲目展示名`() {
        val builtin = FreeSoundSelection.Builtin(SoundSource.PINK_NOISE)
        assertEquals(SoundSource.PINK_NOISE.displayName, builtin.label)

        val local = FreeSoundSelection.LocalMusic(track(id = 5L, name = "我的一曲.flac"))
        assertEquals("我的一曲.flac", local.label)
    }

    @Test
    fun `续播会话携带已完成秒数供startPlan使用`() {
        // 模拟 latestResumableSession 查询结果：RUNNING/PAUSED 且 completedSeconds > 0
        val session = BurnInSession(
            id = 7L,
            presetHours = 120,
            plannedSeconds = BurnPlans.CLASSIC_TOTAL_SECONDS,
            completedSeconds = 11_902L, // 03:18:22
            startedAt = 0L,
            lastUpdatedAt = 0L,
            status = SessionStatus.PAUSED,
        )
        val state = BurnInUiState(resumableSession = session)
        assertEquals(session, state.resumableSession)
        assertTrue(state.resumableSession!!.completedSeconds > 0L)
    }
}
