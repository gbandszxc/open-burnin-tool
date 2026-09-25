package com.github.gbandszxc.obt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SoundSource.fromLegacySoundId] 按原版音源编号反查测试：
 * null/未知编号返回 null（调用方回退不展示音效），合法编号逐一映射回对应枚举
 * （含本地音乐音源占位项 LOCAL_TRACK 的 7）。
 */
class SoundSourceTest {

    // ---- 非法输入 ----

    @Test
    fun `编号为null时返回null`() {
        assertNull(SoundSource.fromLegacySoundId(null))
    }

    @Test
    fun `未知编号返回null`() {
        assertNull(SoundSource.fromLegacySoundId(-1))
        assertNull(SoundSource.fromLegacySoundId(8))
        assertNull(SoundSource.fromLegacySoundId(99))
    }

    // ---- 合法输入 ----

    @Test
    fun `合法编号映射回对应枚举`() {
        assertEquals(SoundSource.SINE_WAVE, SoundSource.fromLegacySoundId(0))
        assertEquals(SoundSource.PINK_NOISE, SoundSource.fromLegacySoundId(1))
        assertEquals(SoundSource.SQUARE_WAVE, SoundSource.fromLegacySoundId(2))
        assertEquals(SoundSource.WHITE_NOISE, SoundSource.fromLegacySoundId(3))
        assertEquals(SoundSource.LOW_FREQ_SWEEP, SoundSource.fromLegacySoundId(4))
        assertEquals(SoundSource.MIXED_BURN, SoundSource.fromLegacySoundId(5))
        assertEquals(SoundSource.WIDE_FREQ_SWEEP, SoundSource.fromLegacySoundId(6))
        assertEquals(SoundSource.LOCAL_TRACK, SoundSource.fromLegacySoundId(7))
    }

    @Test
    fun `全部枚举的编号可逆映射回自身`() {
        for (source in SoundSource.entries) {
            assertEquals(source, SoundSource.fromLegacySoundId(source.legacySoundId))
        }
    }
}
