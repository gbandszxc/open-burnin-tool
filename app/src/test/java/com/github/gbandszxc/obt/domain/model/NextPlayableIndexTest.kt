package com.github.gbandszxc.obt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 有序歌单「下一可用曲目」定位 [nextPlayableIndex] 的纯逻辑测试：
 * 顺序前进、末尾回绕、跳过失效音轨（含跨回绕）、全部失效返回 null、
 * 单元素歌单回环与空歌单边界。
 */
class NextPlayableIndexTest {

    @Test
    fun `无失效音轨时顺序前进`() {
        val playlist = listOf(10L, 20L, 30L)
        assertEquals(1, nextPlayableIndex(playlist, 0, emptySet()))
        assertEquals(2, nextPlayableIndex(playlist, 1, emptySet()))
    }

    @Test
    fun `末尾回绕到歌单头`() {
        val playlist = listOf(10L, 20L, 30L)
        assertEquals(0, nextPlayableIndex(playlist, 2, emptySet()))
    }

    @Test
    fun `跳过失效音轨取下一条可用`() {
        val playlist = listOf(10L, 20L, 30L)
        // 20 失效：0 → 2
        assertEquals(2, nextPlayableIndex(playlist, 0, setOf(20L)))
        // 20/30 均失效：从 0 的下一位起 1(20)、2(30) 皆失效，绕回 0(10) 自身可用
        assertEquals(0, nextPlayableIndex(playlist, 0, setOf(20L, 30L)))
    }

    @Test
    fun `跳过失效音轨跨回绕`() {
        val playlist = listOf(10L, 20L, 30L)
        // 从末位(30)推进：0(10) 失效 → 落到 1(20)
        assertEquals(1, nextPlayableIndex(playlist, 2, setOf(10L)))
        // 0/1 均失效：从末位绕一圈落在 2(30)
        assertEquals(2, nextPlayableIndex(playlist, 2, setOf(10L, 20L)))
    }

    @Test
    fun `全部失效返回null`() {
        val playlist = listOf(10L, 20L, 30L)
        assertNull(nextPlayableIndex(playlist, 0, setOf(10L, 20L, 30L)))
        // 当前曲目起播失败后（from 已入失效集）从下一位找，其余也全失效
        assertNull(nextPlayableIndex(playlist, 0, setOf(30L, 10L, 20L)))
    }

    @Test
    fun `单元素歌单回环到自身`() {
        val playlist = listOf(10L)
        // 单曲循环：播完回环到自身继续
        assertEquals(0, nextPlayableIndex(playlist, 0, emptySet()))
        // 唯一曲目失效则无路可走
        assertNull(nextPlayableIndex(playlist, 0, setOf(10L)))
    }

    @Test
    fun `空歌单返回null`() {
        assertNull(nextPlayableIndex(emptyList(), 0, emptySet()))
    }

    @Test
    fun `from越界按模运算回环`() {
        val playlist = listOf(10L, 20L)
        assertEquals(0, nextPlayableIndex(playlist, 1, emptySet()))
        assertEquals(0, nextPlayableIndex(playlist, 3, emptySet()))
    }
}
