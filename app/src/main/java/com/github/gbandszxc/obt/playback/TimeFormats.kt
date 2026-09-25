package com.github.gbandszxc.obt.playback

import java.util.Locale

/**
 * 时长格式化（纯函数，可 JVM 单测）：煲机时长以天计，分层显示避免超长字符串。
 *
 * - 不足 1 小时：`mm:ss`
 * - 不足 1 天：`HH:mm:ss`
 * - 跨天：`d天 HH:mm:ss`
 *
 * 负数输入按 0 收敛（进度推导已在 [PlaybackState.remainingSeconds] 兜底，这里双保险）。
 */
fun formatBurnDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val days = seconds / SECONDS_PER_DAY
    val hours = (seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val secs = seconds % SECONDS_PER_MINUTE
    return when {
        days > 0L -> String.format(Locale.US, "%d天 %02d:%02d:%02d", days, hours, minutes, secs)
        hours > 0L -> String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        else -> String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
