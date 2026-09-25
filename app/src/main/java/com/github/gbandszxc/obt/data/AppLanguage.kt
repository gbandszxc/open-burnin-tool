package com.github.gbandszxc.obt.data

/**
 * 应用界面语言：
 * - [SYSTEM] 跟随系统语言自动检测（缺省）：中文系统走 values-zh，其余走默认英文资源；
 * - [CHINESE] / [ENGLISH] 应用内强制简体中文 / 英文（覆盖系统语言）。
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    CHINESE("zh-CN"),
    ENGLISH("en"),
    ;

    companion object {
        /** 从持久化字符串解析，空值/非法值（含历史脏数据）一律回退 [SYSTEM]，向前兼容枚举演进。 */
        fun fromRaw(raw: String?): AppLanguage = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}
