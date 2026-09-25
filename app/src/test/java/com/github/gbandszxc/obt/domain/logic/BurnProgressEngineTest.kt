package com.github.gbandszxc.obt.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BurnProgressEngine] 的纯逻辑测试：tick 累加、剩余计算、完成判定、锚点推进与暂停隔离。 */
class BurnProgressEngineTest {

    // ---- tick 累加 ----

    @Test
    fun `tick 累加已完成秒数并计算剩余`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        engine.tick(3L)
        assertEquals(3L, engine.completedSeconds)
        assertEquals(7L, engine.remainingSeconds)
        assertEquals(EngineStatus.RUNNING, engine.status)
    }

    @Test
    fun `非正数增量被忽略`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        engine.tick(0L)
        engine.tick(-5L)
        assertEquals(0L, engine.completedSeconds)
    }

    @Test
    fun `多次 tick 持续累加`() {
        val engine = BurnProgressEngine(plannedSeconds = 100L)
        repeat(10) { engine.tick(7L) }
        assertEquals(70L, engine.completedSeconds)
        assertEquals(30L, engine.remainingSeconds)
    }

    // ---- 完成判定与封顶 ----

    @Test
    fun `超过计划值时封顶并转入完成态`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        val status = engine.tick(99L)
        assertEquals(EngineStatus.COMPLETED, status)
        assertTrue(engine.isCompleted)
        assertEquals(10L, engine.completedSeconds)
        assertEquals(0L, engine.remainingSeconds)
        assertEquals(1.0, engine.progressFraction, 1e-9)
    }

    @Test
    fun `恰好到达计划值即完成`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        engine.tick(5L)
        engine.tick(5L)
        assertTrue(engine.isCompleted)
    }

    @Test
    fun `完成后再 tick 不再变化`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        engine.tick(10L)
        engine.tick(10L)
        assertEquals(10L, engine.completedSeconds)
        assertEquals(EngineStatus.COMPLETED, engine.status)
    }

    @Test
    fun `完成态下 pause 与 resume 均不生效`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        engine.tick(10L)
        assertFalse(engine.pause())
        assertFalse(engine.resume())
        assertEquals(EngineStatus.COMPLETED, engine.status)
    }

    // ---- 暂停与恢复 ----

    @Test
    fun `暂停期间 tick 不累加`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        engine.tick(3L)
        assertTrue(engine.pause())
        assertEquals(EngineStatus.PAUSED, engine.status)
        engine.tick(100L)
        assertEquals(3L, engine.completedSeconds)
    }

    @Test
    fun `恢复后继续累加`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L)
        engine.tick(3L)
        engine.pause()
        engine.resume()
        assertEquals(EngineStatus.RUNNING, engine.status)
        engine.tick(2L)
        assertEquals(5L, engine.completedSeconds)
    }

    // ---- 锚点推进（advanceTo，模拟 elapsedRealtime 单调时钟） ----

    @Test
    fun `首次 advanceTo 只建立锚点不累加`() {
        val engine = BurnProgressEngine(plannedSeconds = 100L)
        engine.advanceTo(50_000L)
        assertEquals(0L, engine.completedSeconds)
    }

    @Test
    fun `advanceTo 按锚点差值折算秒数`() {
        val engine = BurnProgressEngine(plannedSeconds = 100L)
        engine.advanceTo(0L)
        engine.advanceTo(2_500L) // 不足 3 秒，折 2 秒
        assertEquals(2L, engine.completedSeconds)
        engine.advanceTo(3_800L) // 差 1.3 秒，折 1 秒
        assertEquals(3L, engine.completedSeconds)
    }

    @Test
    fun `时间回拨按零处理`() {
        val engine = BurnProgressEngine(plannedSeconds = 100L)
        engine.advanceTo(10_000L)
        engine.advanceTo(5_000L)
        assertEquals(0L, engine.completedSeconds)
    }

    @Test
    fun `暂停隔离锚点期间流逝的时间`() {
        val engine = BurnProgressEngine(plannedSeconds = 100L)
        engine.advanceTo(0L)
        engine.advanceTo(10_000L) // 累计 10 秒
        assertEquals(10L, engine.completedSeconds)
        engine.pause()
        engine.advanceTo(60_000L) // 暂停期间流逝 50 秒，不应计入
        engine.resume()
        engine.advanceTo(61_000L) // 恢复后 1 秒
        assertEquals(11L, engine.completedSeconds)
    }

    // ---- 续播（从持久层恢复初始进度） ----

    @Test
    fun `以初始进度续播并完成`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L, initialCompletedSeconds = 8L)
        assertEquals(8L, engine.completedSeconds)
        assertEquals(2L, engine.remainingSeconds)
        engine.tick(2L)
        assertTrue(engine.isCompleted)
    }

    @Test
    fun `初始进度已达计划值直接进入完成态`() {
        val engine = BurnProgressEngine(plannedSeconds = 10L, initialCompletedSeconds = 10L)
        assertEquals(EngineStatus.COMPLETED, engine.status)
        assertTrue(engine.isCompleted)
    }

    @Test
    fun `初始进度越界被拒绝`() {
        try {
            BurnProgressEngine(plannedSeconds = 10L, initialCompletedSeconds = 11L)
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 预期路径
        }
    }

    @Test
    fun `计划时长必须为正数`() {
        try {
            BurnProgressEngine(plannedSeconds = 0L)
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 预期路径
        }
    }
}
