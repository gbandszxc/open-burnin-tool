package com.github.gbandszxc.obt.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveAudioExtension] 纯逻辑测试：导入本地音乐时从展示名解析存储扩展名，
 * 取不到/不合法一律回退 ".audio"。
 */
class TrackRepositoryExtensionTest {

    @Test
    fun `常规音频扩展名解析并转小写`() {
        assertEquals(".mp3", resolveAudioExtension("song.mp3"))
        assertEquals(".flac", resolveAudioExtension("Track.FLAC"))
        assertEquals(".ogg", resolveAudioExtension("song.OGG"))
        assertEquals(".m4a", resolveAudioExtension("我的歌.m4a"))
        assertEquals(".12345678", resolveAudioExtension("name.12345678"))
    }

    @Test
    fun `取不到扩展名时回退audio`() {
        assertEquals(".audio", resolveAudioExtension(null))
        assertEquals(".audio", resolveAudioExtension(""))
        assertEquals(".audio", resolveAudioExtension("   "))
        assertEquals(".audio", resolveAudioExtension("noext"))
        assertEquals(".audio", resolveAudioExtension("trailing."))
        assertEquals(".audio", resolveAudioExtension(".hidden"))
    }

    @Test
    fun `不合法扩展名一律回退audio`() {
        // 含空格/符号/非 ASCII：不作为扩展名落盘，避免拼出异常文件名
        assertEquals(".audio", resolveAudioExtension("a.b c"))
        assertEquals(".audio", resolveAudioExtension("a.mp 3"))
        assertEquals(".audio", resolveAudioExtension("a.无.损"))
        assertEquals(".audio", resolveAudioExtension("a.无损"))
        assertEquals(".audio", resolveAudioExtension("a.a/b"))
        assertEquals(".audio", resolveAudioExtension("a.123456789"))
    }
}
