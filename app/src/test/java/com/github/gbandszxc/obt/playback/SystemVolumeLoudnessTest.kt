package com.github.gbandszxc.obt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [streamVolumeIndexFor] 系统媒体音量档位映射测试：
 * 标准四阶段比例（1/5、1/3、7/15、3/5）在满档 15 的换算、1.0 满档、小比例保底 1 档、
 * 0 比例静音、半档四舍五入、越界比例防御收敛与 maxIndex = 0 边界。
 */
class SystemVolumeLoudnessTest {

    @Test
    fun `标准四阶段比例在满档15下的换算与逆向结论一致`() {
        assertEquals(3, streamVolumeIndexFor(1.0 / 5.0, 15)) // 0.2 × 15 = 3
        assertEquals(5, streamVolumeIndexFor(1.0 / 3.0, 15)) // 1/3 × 15 ≈ 5
        assertEquals(7, streamVolumeIndexFor(7.0 / 15.0, 15)) // 7/15 × 15 = 7
        assertEquals(9, streamVolumeIndexFor(3.0 / 5.0, 15)) // 0.6 × 15 = 9
    }

    @Test
    fun `比例1_0映射为最大档位`() {
        assertEquals(15, streamVolumeIndexFor(1.0, 15))
        assertEquals(100, streamVolumeIndexFor(1.0, 100))
        assertEquals(7, streamVolumeIndexFor(1.0, 7))
    }

    @Test
    fun `小比例向上保底为1档不完全静音`() {
        // 0.01 × 15 = 0.15 → round 0 → 比例大于 0 时至少 1 档
        assertEquals(1, streamVolumeIndexFor(0.01, 15))
        // 0.07 × 15 = 1.05 → round 1，不额外抬升
        assertEquals(1, streamVolumeIndexFor(0.07, 15))
        // 极小满档同样保底且不超过 maxIndex
        assertEquals(1, streamVolumeIndexFor(0.3, 2))
    }

    @Test
    fun `比例为0映射为0档`() {
        assertEquals(0, streamVolumeIndexFor(0.0, 15))
        assertEquals(0, streamVolumeIndexFor(0.0, 0))
    }

    @Test
    fun `半档四舍五入向上`() {
        // 0.5 × 15 = 7.5 → 8
        assertEquals(8, streamVolumeIndexFor(0.5, 15))
        // 0.5 × 5 = 2.5 → 3
        assertEquals(3, streamVolumeIndexFor(0.5, 5))
    }

    @Test
    fun `越界比例防御性收敛到边界`() {
        assertEquals(0, streamVolumeIndexFor(-0.5, 15)) // 负比例收敛为 0（静音）
        assertEquals(15, streamVolumeIndexFor(1.5, 15)) // 超过 1 收敛为满档
        assertEquals(2, streamVolumeIndexFor(9.0, 2)) // 超过 1 收敛且不超过 maxIndex
    }

    @Test
    fun `maxIndex为0时恒为0档`() {
        assertEquals(0, streamVolumeIndexFor(0.2, 0))
        assertEquals(0, streamVolumeIndexFor(1.0, 0))
    }
}
