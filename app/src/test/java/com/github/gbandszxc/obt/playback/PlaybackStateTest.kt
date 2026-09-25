package com.github.gbandszxc.obt.playback

import com.github.gbandszxc.obt.domain.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PlaybackState] 派生值：剩余秒收敛、暂停判定、进度比例与空闲态边界。 */
class PlaybackStateTest {

    @Test
    fun `剩余秒等于计划减已煲`() {
        val state = PlaybackState(
            status = PlaybackStatus.PLAYING,
            plannedSeconds = 7_200L,
            completedSeconds = 1_234L,
        )
        assertEquals(5_966L, state.remainingSeconds)
        assertFalse(state.isPaused)
    }

    @Test
    fun `已煲超出计划时剩余收敛为 0`() {
        val state = PlaybackState(plannedSeconds = 100L, completedSeconds = 150L)
        assertEquals(0L, state.remainingSeconds)
        assertEquals(1.5, state.progressFraction, 1e-9)
    }

    @Test
    fun `暂停态判定`() {
        val paused = PlaybackState(status = PlaybackStatus.PAUSED, plannedSeconds = 10L, completedSeconds = 3L)
        assertTrue(paused.isPaused)
        assertEquals(7L, paused.remainingSeconds)
    }

    @Test
    fun `空闲态零值边界`() {
        val idle = PlaybackState.IDLE
        assertEquals(PlaybackStatus.IDLE, idle.status)
        assertEquals(0L, idle.remainingSeconds)
        assertEquals(0.0, idle.progressFraction, 1e-9)
        assertEquals(0, idle.phaseIndex)
        assertNull(idle.soundSource)
        assertNull(idle.localTrackName)
        // 空闲无阶段：阶段固定身份缺省为 null（展示层阶段名解析回退兜底）
        assertNull(idle.stageId)
        assertFalse(idle.isPaused)
    }

    @Test
    fun `结构化播放身份随状态透传`() {
        // 展示名不进状态：只携带阶段位置序号 + 阶段固定身份 + 音源枚举（含轮换结果），
        // 显示文案由展示层解析——阶段名按 stageId 经语言资源解析，「阶段 i/N」的 i 用 phaseIndex
        val state = PlaybackState(
            status = PlaybackStatus.PLAYING,
            planId = "classic_120h",
            phaseIndex = 0,
            stageId = 3,
            soundSource = SoundSource.PINK_NOISE,
        )
        assertEquals("classic_120h", state.planId)
        assertEquals(0, state.phaseIndex)
        assertEquals(3, state.stageId)
        assertEquals(SoundSource.PINK_NOISE, state.soundSource)
    }
}
