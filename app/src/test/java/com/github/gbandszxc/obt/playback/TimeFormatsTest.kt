package com.github.gbandszxc.obt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatBurnDuration] 的分层显示规则：mm:ss / HH:mm:ss / 跨天模板，负数收敛 0。
 * 跨天单位词随应用语言由调用点传模板（缺省英文风格，应用内传 duration_cross_day_fmt 资源）。
 */
class TimeFormatsTest {

    /** 中文跨天模板（与 values-zh 的 duration_cross_day_fmt 一致，验证模板注入路径）。 */
    private val zhCrossDay = "%1\$d天 %2$02d:%3$02d:%4$02d"

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
    fun `跨天按缺省模板显示`() {
        assertEquals("1d 00:00:00", formatBurnDuration(86_400L))
        assertEquals("2d 03:24:05", formatBurnDuration(2L * 86_400 + 3 * 3_600 + 24 * 60 + 5))
        // 原版默认方案总时长 432000s = 120 小时 = 5 天
        assertEquals("5d 00:00:00", formatBurnDuration(432_000L))
    }

    @Test
    fun `跨天模板按调用点语言注入`() {
        assertEquals("1天 00:00:00", formatBurnDuration(86_400L, zhCrossDay))
        assertEquals("5天 03:24:05", formatBurnDuration(5L * 86_400 + 3 * 3_600 + 24 * 60 + 5, zhCrossDay))
    }

    @Test
    fun `负数收敛为 0`() {
        assertEquals("00:00", formatBurnDuration(-1L))
    }
}
