package com.github.gbandszxc.obt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地音乐自定义音源的方案模型测试：
 * - [BurnPhase.localTrackId] / [BurnPhase.localTrackIds] 新增默认参数的向后兼容（历史调用零改动）；
 * - 歌单字段的模型约束（id 正数且唯一、与轮换互斥、音源强制 LOCAL_TRACK）；
 * - [BurnPlans.quick] 本地音乐参数组合（soundSource 强制 LOCAL_TRACK、阶段名/显示名用 soundLabel）。
 */
class BurnLocalTrackPlanTest {

    // ---- BurnPhase 默认参数兼容性 ----

    @Test
    fun `BurnPhase缺省localTrackId为null且历史构造方式等价`() {
        val legacy = BurnPhase(0, "gentle", 100L, SoundSource.WHITE_NOISE, 0.2)
        val explicit = BurnPhase(
            index = 0,
            name = "gentle",
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
    fun `BurnPhase缺省localTrackIds为空且与localTrackId相互独立`() {
        val phase = BurnPhase(0, "gentle", 100L, SoundSource.WHITE_NOISE, 0.2)
        assertTrue(phase.localTrackIds.isEmpty())
        // 单音轨字段不牵动歌单字段；历史构造点零改动
        val single = phase.copy(localTrackId = 9L)
        assertTrue(single.localTrackIds.isEmpty())
        // 歌单字段独立赋值（歌单要求音源为 LOCAL_TRACK，换音源验证互不牵动）
        val playlist = single.copy(
            localTrackId = null,
            soundSource = SoundSource.LOCAL_TRACK,
            localTrackIds = listOf(1L, 2L),
        )
        assertNull(playlist.localTrackId)
        assertEquals(listOf(1L, 2L), playlist.localTrackIds)
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

    // ---- BurnPhase 有序歌单约束 ----

    @Test
    fun `BurnPhase携带歌单时音源音量轮换默认参数按约束成立`() {
        val phase = BurnPhase(
            index = 2,
            name = "steady_music",
            durationSeconds = 100L,
            soundSource = SoundSource.LOCAL_TRACK,
            volumeRatio = 0.5,
            stageId = 2,
            localTrackIds = listOf(3L, 1L, 2L),
        )
        assertEquals(listOf(3L, 1L, 2L), phase.localTrackIds)
        assertNull(phase.alternateWith)
        assertNull(phase.alternateEverySeconds)
        // 无轮换配置，soundSourceAt 直通 LOCAL_TRACK 占位枚举（时序器同语义）
        assertEquals(SoundSource.LOCAL_TRACK, phase.soundSourceAt(0L))
        assertEquals(SoundSource.LOCAL_TRACK, phase.soundSourceAt(99L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BurnPhase歌单音轨id非正被拒绝`() {
        BurnPhase(0, "坏歌单", 100L, SoundSource.LOCAL_TRACK, 0.2, stageId = 0, localTrackIds = listOf(1L, 0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BurnPhase歌单音轨id重复被拒绝`() {
        BurnPhase(0, "重歌单", 100L, SoundSource.LOCAL_TRACK, 0.2, stageId = 0, localTrackIds = listOf(5L, 5L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BurnPhase歌单与轮换互斥`() {
        BurnPhase(
            0,
            "又轮换又歌单",
            100L,
            SoundSource.LOCAL_TRACK,
            0.2,
            alternateWith = SoundSource.PINK_NOISE,
            alternateEverySeconds = 10L,
            stageId = 0,
            localTrackIds = listOf(1L),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BurnPhase歌单要求音源为LOCAL_TRACK`() {
        BurnPhase(0, "白噪带歌单", 100L, SoundSource.WHITE_NOISE, 0.2, stageId = 0, localTrackIds = listOf(1L))
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
        // phase.name 是日志/测试用内部标识：带 soundLabel（曲目名）便于日志定位；plan.name 即 planId
        assertEquals("我的一曲.flac", plan.phases[0].name)
        assertEquals("quick_8h", plan.name)
    }

    @Test
    fun `quick本地音乐_未传或空白标签回退默认文案`() {
        val unlabeled = BurnPlans.quick(2, localTrackId = 7L)
        assertEquals("local_track", unlabeled.phases[0].name)
        assertEquals("quick_2h", unlabeled.name)

        val blankLabeled = BurnPlans.quick(2, localTrackId = 7L, soundLabel = "   ")
        assertEquals("local_track", blankLabeled.phases[0].name)
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
        assertEquals("quick_8h", plan.name)
        val phase = plan.phases[0]
        assertNull(phase.localTrackId)
        assertTrue(phase.localTrackIds.isEmpty())
        assertEquals("quick_WHITE_NOISE", phase.name)
        assertEquals(SoundSource.WHITE_NOISE, phase.soundSource)
    }
}
