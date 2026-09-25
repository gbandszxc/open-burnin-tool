package com.github.gbandszxc.obt.playback

import java.util.Locale

/**
 * 时长格式化（纯函数，可 JVM 单测）：煲机时长以天计，分层显示避免超长字符串。
 *
 * - 不足 1 小时：`mm:ss`
 * - 不足 1 天：`HH:mm:ss`
 * - 跨天：`d天 HH:mm:ss`（跨天单位词随应用语言，由调用点经 [crossDayTemplate] 传入，
 *   默认英文风格 `%1$dd %2$02d:%3$02d:%4$02d`；资源模板见 strings 的 duration_cross_day_fmt）
 *
 * 负数输入按 0 收敛（进度推导已在 [PlaybackState.remainingSeconds] 兜底，这里双保险）。
 */
fun formatBurnDuration(
    totalSeconds: Long,
    crossDayTemplate: String = DEFAULT_CROSS_DAY_TEMPLATE,
): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val days = seconds / SECONDS_PER_DAY
    val hours = (seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val secs = seconds % SECONDS_PER_MINUTE
    return when {
        days > 0L -> String.format(Locale.US, crossDayTemplate, days, hours, minutes, secs)
        hours > 0L -> String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        else -> String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

/** 跨天段缺省模板（英文风格）；应用内展示一律传当前语言的资源模板。 */
const val DEFAULT_CROSS_DAY_TEMPLATE = "%1\$dd %2$02d:%3$02d:%4$02d"

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
