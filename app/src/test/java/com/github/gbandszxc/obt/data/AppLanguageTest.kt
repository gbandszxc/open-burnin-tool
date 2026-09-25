package com.github.gbandszxc.obt.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** [AppLanguage] 持久化字符串解析：合法值透传，空值/脏数据回退跟随系统（自动检测）。 */
class AppLanguageTest {

    @Test
    fun `合法值按名称解析`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw("SYSTEM"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromRaw("CHINESE"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromRaw("ENGLISH"))
    }

    @Test
    fun `空值与非法值回退跟随系统`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw("english"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw("legacy_junk"))
    }

    @Test
    fun `指定语言携带 BCP-47 标签，跟随系统为 null`() {
        assertEquals(null, AppLanguage.SYSTEM.tag)
        assertEquals("zh-CN", AppLanguage.CHINESE.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
    }
}
