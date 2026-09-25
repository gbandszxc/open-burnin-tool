package com.github.gbandszxc.obt.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatDurationHuman] 粗粒度时长文案的分段与收敛行为。
 * 单位词随应用语言：测试用 [HumanDurationPatterns.zh] 显式构造（应用内经 fromResources 注入）。
 */
class DurationFormatsTest {

    private val patterns = HumanDurationPatterns.zh

    @Test
    fun `负数收敛为不足 1 分钟`() {
        assertEquals("不足 1 分钟", formatDurationHuman(-5L, patterns))
    }

    @Test
    fun `秒级显示不足 1 分钟`() {
        assertEquals("不足 1 分钟", formatDurationHuman(59L, patterns))
    }

    @Test
    fun `分钟段`() {
        assertEquals("45 分钟", formatDurationHuman(45 * 60L, patterns))
    }

    @Test
    fun `小时段带分钟`() {
        assertEquals("5 小时 30 分钟", formatDurationHuman(5 * 3_600L + 30 * 60L, patterns))
    }

    @Test
    fun `小时段整点不带分钟`() {
        assertEquals("5 小时", formatDurationHuman(5 * 3_600L, patterns))
    }

    @Test
    fun `跨天段余数小时`() {
        assertEquals("3 天 2 小时", formatDurationHuman(3 * 86_400L + 2 * 3_600L, patterns))
    }

    @Test
    fun `跨天段整天`() {
        assertEquals("3 天 0 小时", formatDurationHuman(3 * 86_400L, patterns))
    }
}
