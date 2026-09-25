package com.github.gbandszxc.obt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** [formatBurnDuration] 的分层显示规则：mm:ss / HH:mm:ss / d天 HH:mm:ss，负数收敛 0。 */
class TimeFormatsTest {

    @Test
    fun `不足一小时按 mm-ss 显示`() {
        assertEquals("00:00", formatBurnDuration(0L))
        assertEquals("00:59", formatBurnDuration(59L))
        assertEquals("05:07", formatBurnDuration(5L * 60 + 7))
        assertEquals("59:59", formatBurnDuration(59L * 60 + 59))
    }

    @Test
    fun `一小时起按 HH-mm-ss 显示`() {
        assertEquals("01:00:00", formatBurnDuration(3_600L))
        assertEquals("02:03:04", formatBurnDuration(2L * 3_600 + 3 * 60 + 4))
        assertEquals("23:59:59", formatBurnDuration(86_399L))
    }

    @Test
    fun `跨天按 x天 HH-mm-ss 显示`() {
        assertEquals("1天 00:00:00", formatBurnDuration(86_400L))
        assertEquals("2天 03:24:05", formatBurnDuration(2L * 86_400 + 3 * 3_600 + 24 * 60 + 5))
        // 原版默认方案总时长 432000s = 120 小时 = 5 天
        assertEquals("5天 00:00:00", formatBurnDuration(432_000L))
    }

    @Test
    fun `负数收敛为 0`() {
        assertEquals("00:00", formatBurnDuration(-1L))
    }
}
