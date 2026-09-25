package com.github.gbandszxc.obt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("", idle.phaseName)
        assertEquals("", idle.soundSourceName)
        assertFalse(idle.isPaused)
    }

    @Test
    fun `当前音源中文名随状态透传`() {
        val state = PlaybackState(
            status = PlaybackStatus.PLAYING,
            phaseName = "打擂",
            soundSourceName = "粉红噪音",
        )
        assertEquals("粉红噪音", state.soundSourceName)
        assertEquals("打擂", state.phaseName)
    }
}
