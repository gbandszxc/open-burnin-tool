package com.github.gbandszxc.obt.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.github.gbandszxc.obt.data.AppLanguage

/**
 * 进程级应用语言状态与上下文包装（单例）。
 *
 * 应用内多语言的统一入口：持久化在 [com.github.gbandszxc.obt.data.SettingsRepository]（DataStore），
 * 本对象持有进程内当前值（启动时由 [com.github.gbandszxc.obt.BurnInApplication] 同步初始化，
 * 切换时先更新内存再落盘），Activity / Service 在 `attachBaseContext` 经 [wrap]
 * 用 `createConfigurationContext` 应用语言，资源系统据此选择 values-zh 或默认英文。
 *
 * 选择手动包装而非 AppCompatDelegate.setApplicationLocales：项目刻意不引 appcompat/material
 * XML 库（见 values/themes.xml 注释），且手动包装能让前台服务通知与 Activity 使用同一套语言
 * 口径，全版本（minSdk 26）行为一致。
 */
object AppLocale {

    /** 当前生效语言；仅在主线程写（Application.onCreate 与设置页切换回调），读取随时发生。 */
    @Volatile
    private var current: AppLanguage = AppLanguage.SYSTEM

    /** 当前生效语言（无持久化读取，进程内存值）。 */
    fun current(): AppLanguage = current

    /** 更新进程内当前语言（设置切换时先调本方法再异步落盘，保证重建的界面立即读到新值）。 */
    fun update(language: AppLanguage) {
        current = language
    }

    /**
     * 按当前语言包装 [base]：
     * - [AppLanguage.SYSTEM]：原样返回，交给系统按系统语言自动匹配资源（自动检测）；
     * - 指定语言：用 `createConfigurationContext` 覆盖 locale 列表（只影响资源解析，
     *   不改动 base 的其他配置）。
     */
    fun wrap(base: Context): Context {
        val tag = current.tag ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}
