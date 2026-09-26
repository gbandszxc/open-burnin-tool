package com.github.gbandszxc.obt.ui.history

import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 记录页会话行方案名 id 解析（[historyPlanIdFor]）的纯 JVM 测试：
 * 方案煲机会话（soundSourceId 为 null）不落 quick——QUICK_HOURS 命中只是 forPresetHours
 * 的预设路由，历史行不得回显「快速煲机」；自由煲机会话（soundSourceId 非空）一律 quick，
 * 小时数不限预设。纯 JVM 断言，不依赖 Android 框架。
 */
class HistoryPlanNameTest {

    /** 构造一条会话行（缺省为有进度的已完成会话，仅 presetHours/soundSourceId 影响断言）。 */
    private fun session(
        presetHours: Int,
        soundSourceId: Int?,
    ) = BurnInSession(
        id = 1L,
        presetHours = presetHours,
        plannedSeconds = presetHours * 3_600L,
        completedSeconds = 3_600L,
        startedAt = 0L,
        lastUpdatedAt = 0L,
        status = SessionStatus.COMPLETED,
        soundSourceId = soundSourceId,
        soundLabel = null,
    )

    // ---- 方案煲机会话（soundSourceId 为 null） ----

    @Test
    fun `方案煲机120小时行解析为经典方案`() {
        assertEquals(BurnPlans.CLASSIC.id, historyPlanIdFor(session(120, soundSourceId = null)))
    }

    @Test
    fun `方案煲机命中快捷预设小时数也解析为自定义方案`() {
        // 8/16 ∈ QUICK_HOURS，但方案煲机绝不产生 quick 方案（回归：自定义 8 小时曾误显「快速煲机 8 小时」）
        assertEquals("custom_8h", historyPlanIdFor(session(8, soundSourceId = null)))
        assertEquals("custom_16h", historyPlanIdFor(session(16, soundSourceId = null)))
    }

    @Test
    fun `方案煲机非预设小时数行解析为自定义方案`() {
        assertEquals("custom_36h", historyPlanIdFor(session(36, soundSourceId = null)))
        assertEquals("custom_999h", historyPlanIdFor(session(999, soundSourceId = null)))
    }

    // ---- 自由煲机会话（soundSourceId 非空） ----

    @Test
    fun `自由煲机行解析为快捷方案`() {
        assertEquals(
            "quick_8h",
            historyPlanIdFor(session(8, soundSourceId = SoundSource.WHITE_NOISE.legacySoundId)),
        )
    }

    @Test
    fun `自由煲机非预设小时数行也解析为快捷方案`() {
        assertEquals(
            "quick_36h",
            historyPlanIdFor(session(36, soundSourceId = SoundSource.PINK_NOISE.legacySoundId)),
        )
    }
}
