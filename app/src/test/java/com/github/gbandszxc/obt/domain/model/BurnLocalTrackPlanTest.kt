package com.github.gbandszxc.obt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地音乐自定义音源的方案模型测试：
 * - [BurnPhase.localTrackId] 新增默认参数的向后兼容（历史调用零改动）；
 * - [BurnPlans.quick] 本地音乐参数组合（soundSource 强制 LOCAL_TRACK、阶段名/显示名用 soundLabel）。
 */
class BurnLocalTrackPlanTest {

    // ---- BurnPhase 默认参数兼容性 ----

    @Test
    fun `BurnPhase缺省localTrackId为null且历史构造方式等价`() {
        val legacy = BurnPhase(0, "舒筋", 100L, SoundSource.WHITE_NOISE, 0.2)
        val explicit = BurnPhase(
            index = 0,
            name = "舒筋",
            durationSeconds = 100L,
            soundSource = SoundSource.WHITE_NOISE,
            volumeRatio = 0.2,
            alternateWith = null,
            alternateEverySeconds = null,
            localTrackId = null,
        )
        assertEquals(explicit, legacy)
        assertNull(legacy.localTrackId)
        // copy 不传即保持 null，既有 custom() 等调用点不受新参数影响
        assertNull(legacy.copy(index = 1).localTrackId)
    }

    @Test
    fun `BurnPhase携带localTrackId时其余默认参数不变`() {
        val phase = BurnPhase(0, "本地曲目", 3_600L, SoundSource.LOCAL_TRACK, 1.0 / 3.0, localTrackId = 9L)
        assertEquals(9L, phase.localTrackId)
        assertNull(phase.alternateWith)
        assertNull(phase.alternateEverySeconds)
        assertEquals(SoundSource.LOCAL_TRACK, phase.soundSourceAt(0L))
        assertEquals(SoundSource.LOCAL_TRACK, phase.soundSourceAt(1_800L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BurnPhase本地音轨id非正被拒绝`() {
        BurnPhase(0, "坏音轨", 100L, SoundSource.LOCAL_TRACK, 0.2, localTrackId = 0L)
    }

    // ---- quick 本地音乐参数组合 ----

    @Test
    fun `quick本地音乐_音源强制LOCAL_TRACK且音量取其默认增益`() {
        val plan = BurnPlans.quick(8, localTrackId = 5L, soundLabel = "我的一曲.flac")
        assertEquals("quick_8h", plan.id)
        assertEquals(8 * 3_600L, plan.totalSeconds)
        assertEquals(1, plan.phases.size)

        val phase = plan.phases[0]
        assertEquals(5L, phase.localTrackId)
        assertEquals(SoundSource.LOCAL_TRACK, phase.soundSource)
        assertEquals(SoundSource.LOCAL_TRACK.defaultGainRatio, phase.volumeRatio, 1e-9)
        assertNull(phase.alternateWith)
        assertNull(phase.alternateEverySeconds)
    }

    @Test
    fun `quick本地音乐_阶段名与显示名用soundLabel`() {
        val plan = BurnPlans.quick(8, localTrackId = 5L, soundLabel = "我的一曲.flac")
        assertEquals("我的一曲.flac", plan.phases[0].name)
        assertEquals("快速煲机 8 小时 · 我的一曲.flac", plan.name)
    }

    @Test
    fun `quick本地音乐_未传或空白标签回退默认文案`() {
        val unlabeled = BurnPlans.quick(2, localTrackId = 7L)
        assertEquals("本地音乐", unlabeled.phases[0].name)
        assertEquals("快速煲机 2 小时 · 本地音乐", unlabeled.name)

        val blankLabeled = BurnPlans.quick(2, localTrackId = 7L, soundLabel = "   ")
        assertEquals("本地音乐", blankLabeled.phases[0].name)
    }

    @Test
    fun `quick本地音乐时忽略sound参数`() {
        val plan = BurnPlans.quick(8, SoundSource.PINK_NOISE, localTrackId = 3L, soundLabel = "曲目A")
        assertEquals(SoundSource.LOCAL_TRACK, plan.phases[0].soundSource)
        assertEquals(3L, plan.phases[0].localTrackId)
        assertEquals(SoundSource.LOCAL_TRACK.defaultGainRatio, plan.phases[0].volumeRatio, 1e-9)
    }

    @Test
    fun `quick缺省调用不受新参数影响`() {
        val plan = BurnPlans.quick(8)
        assertEquals("quick_8h", plan.id)
        assertEquals("快速煲机 8 小时", plan.name)
        val phase = plan.phases[0]
        assertNull(phase.localTrackId)
        assertEquals("快速白噪", phase.name)
        assertEquals(SoundSource.WHITE_NOISE, phase.soundSource)
    }
}
