package com.github.gbandszxc.obt.domain.logic

import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [BurnSequencer] 的纯逻辑测试：标准四阶段边界定位、阶段音源与轮换阶段 30 分钟轮换。
 * 边界值取自方案定义（阶段时长/音量沿原版逆向结论，音源为内置音乐移除后的本版编排）：
 * 舒缓 0..43199 白噪 / 适应 43200..86399 粉噪 / 稳定 86400..345599 粉噪恒定 /
 * 轮换 345600..431999 白噪↔粉噪按「阶段内剩余秒/1800」奇偶轮换（偶→白噪、奇→粉噪）。
 */
class BurnSequencerTest {

    private lateinit var sequencer: BurnSequencer

    @Before
    fun setUp() {
        sequencer = BurnSequencer(BurnPlans.CLASSIC)
    }

    // ---- 阶段边界 ----

    @Test
    fun `第0秒位于舒缓阶段并使用白噪`() {
        val position = sequencer.positionAt(0L)
        assertEquals(0, position.phaseIndex)
        assertEquals("gentle", position.phase.name)
        assertEquals(SoundSource.WHITE_NOISE, position.soundSource)
        assertEquals(1.0 / 5.0, position.volumeRatio, 1e-9)
        assertEquals(43_200L, position.phaseRemainingSeconds)
        assertEquals(432_000L, position.planRemainingSeconds)
    }

    @Test
    fun `舒缓阶段最后一秒仍在第0阶段`() {
        val position = sequencer.positionAt(43_199L)
        assertEquals(0, position.phaseIndex)
        assertEquals(1L, position.phaseRemainingSeconds)
    }

    @Test
    fun `43200秒切换到适应阶段使用粉噪`() {
        val position = sequencer.positionAt(43_200L)
        assertEquals(1, position.phaseIndex)
        assertEquals("adapt", position.phase.name)
        assertEquals(SoundSource.PINK_NOISE, position.soundSource)
        assertEquals(1.0 / 3.0, position.volumeRatio, 1e-9)
    }

    @Test
    fun `86400秒切换到稳定阶段以粉噪开场`() {
        val position = sequencer.positionAt(86_400L)
        assertEquals(2, position.phaseIndex)
        assertEquals("steady", position.phase.name)
        assertEquals(SoundSource.PINK_NOISE, position.soundSource)
        assertEquals(7.0 / 15.0, position.volumeRatio, 1e-9)
    }

    @Test
    fun `345600秒切换到轮换阶段以白噪开场`() {
        val position = sequencer.positionAt(345_600L)
        assertEquals(3, position.phaseIndex)
        assertEquals("alternate", position.phase.name)
        // 轮换开场：阶段剩余 86400 秒 = 48 × 1800，偶数段 → 基准音源白噪（沿原版剩余秒奇偶判定）
        assertEquals(SoundSource.WHITE_NOISE, position.soundSource)
        assertEquals(3.0 / 5.0, position.volumeRatio, 1e-9)
    }

    @Test
    fun `方案末尾返回轮换阶段结束点`() {
        val position = sequencer.positionAt(432_000L)
        assertEquals(3, position.phaseIndex)
        assertEquals(86_400L, position.elapsedInPhaseSeconds)
        assertEquals(0L, position.phaseRemainingSeconds)
        assertEquals(0L, position.planRemainingSeconds)
    }

    @Test
    fun `越界输入按边界收敛`() {
        assertEquals(0, sequencer.positionAt(-1L).phaseIndex)
        assertEquals(0L, sequencer.positionAt(-1L).elapsedInPhaseSeconds)
        assertEquals(3, sequencer.positionAt(999_999L).phaseIndex)
    }

    // ---- 稳定阶段：粉噪恒定（无轮换配置） ----

    @Test
    fun `稳定阶段全程恒定粉噪不轮换`() {
        // 覆盖 30 分钟轮换点与其余采样点：若错误地配置轮换，这些点会落到白噪
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(86_400L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(86_400L + 1_799L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(86_400L + 1_800L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(86_400L + 3_599L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(86_400L + 3_600L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(86_400L + 130_000L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(345_599L).soundSource)
    }

    // ---- 轮换阶段：按阶段内剩余秒每 30 分钟轮换（偶→白噪、奇→粉噪） ----

    @Test
    fun `轮换开场先播1秒白噪随后进入粉噪段`() {
        // 剩余 86400（=48×1800，偶）→ 白噪；剩余 86399（47，奇）→ 粉噪
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(345_600L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(345_601L).soundSource)
    }

    @Test
    fun `轮换阶段轮换段与剩余秒奇偶逐秒对齐`() {
        // 剩余 84600（47，奇）→ 粉噪；剩余 84599（46，偶）→ 白噪
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(345_600L + 1_800L).soundSource)
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(345_600L + 1_801L).soundSource)
        // 剩余 82800（46，偶）→ 白噪；剩余 82799（45，奇）→ 粉噪
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(345_600L + 3_600L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(345_600L + 3_601L).soundSource)
    }

    @Test
    fun `轮换阶段末尾回到白噪段`() {
        // 剩余 3600（2，偶）→ 白噪；剩余 3599（1，奇）→ 粉噪；剩余 1800（1，奇）→ 粉噪；
        // 剩余 1799（0，偶）→ 白噪；最后一秒剩余 1（偶）→ 白噪
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(345_600L + 82_800L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(345_600L + 82_801L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(345_600L + 84_600L).soundSource)
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(345_600L + 84_601L).soundSource)
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(431_999L).soundSource)
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(432_000L).soundSource)
    }

    // ---- 非轮换阶段 ----

    @Test
    fun `非轮换阶段始终使用基准音源`() {
        assertEquals(SoundSource.WHITE_NOISE, sequencer.positionAt(10_000L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(60_000L).soundSource)
        assertEquals(SoundSource.PINK_NOISE, sequencer.positionAt(200_000L).soundSource)
    }

    // ---- 阶段内进度 ----

    @Test
    fun `阶段内已播与剩余秒数计算正确`() {
        val position = sequencer.positionAt(43_201L)
        assertEquals(1, position.phaseIndex)
        assertEquals(1L, position.elapsedInPhaseSeconds)
        assertEquals(43_199L, position.phaseRemainingSeconds)
        assertEquals(432_000L - 43_201L, position.planRemainingSeconds)
    }
}
