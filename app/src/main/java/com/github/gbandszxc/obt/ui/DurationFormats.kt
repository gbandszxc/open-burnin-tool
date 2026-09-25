package com.github.gbandszxc.obt.ui

/**
 * 面向人的粗粒度时长文案（区别于 playback 层逐秒精度的 [com.github.gbandszxc.obt.playback.formatBurnDuration]）：
 * 「3 天 2 小时」「5 小时 30 分钟」「45 分钟」「不足 1 分钟」。
 * 用于累计煲机小结等非计时场景。负数输入按 0 收敛。
 */
fun formatDurationHuman(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val days = seconds / SECONDS_PER_DAY
    val hours = (seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return when {
        days > 0L -> "$days 天 $hours 小时"
        hours > 0L -> if (minutes > 0L) "$hours 小时 $minutes 分钟" else "$hours 小时"
        minutes > 0L -> "$minutes 分钟"
        else -> "不足 1 分钟"
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
