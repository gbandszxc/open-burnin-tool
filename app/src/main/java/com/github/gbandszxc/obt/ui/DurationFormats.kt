package com.github.gbandszxc.obt.ui

import android.content.Context
import com.github.gbandszxc.obt.R

/**
 * 面向人的粗粒度时长文案（区别于 playback 层逐秒精度的
 * [com.github.gbandszxc.obt.playback.formatBurnDuration]）：
 * 「3 天 2 小时」「5 小时 30 分钟」「45 分钟」「不足 1 分钟」。
 * 用于累计煲机小结等非计时场景。负数输入按 0 收敛。
 *
 * 单位词随应用语言：调用点经 [HumanDurationPatterns.fromResources] 取当前语言模板传入，
 * 保持本函数为纯函数可 JVM 单测（测试用 [HumanDurationPatterns] 显式构造）。
 */
fun formatDurationHuman(totalSeconds: Long, patterns: HumanDurationPatterns): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val days = seconds / SECONDS_PER_DAY
    val hours = (seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return when {
        days > 0L -> patterns.daysHours.format(days, hours)
        hours > 0L -> if (minutes > 0L) patterns.hoursMinutes.format(hours, minutes) else patterns.hoursOnly.format(hours)
        minutes > 0L -> patterns.minutesOnly.format(minutes)
        else -> patterns.underMinute
    }
}

/** 粗粒度时长的各段模板集合（`%1$d` 等位置参数，与 strings 资源模板一一对应）。 */
data class HumanDurationPatterns(
    val daysHours: String,
    val hoursMinutes: String,
    val hoursOnly: String,
    val minutesOnly: String,
    val underMinute: String,
) {
    private fun String.format(vararg args: Any) = java.lang.String.format(this, *args)

    companion object {
        /** 按当前应用语言从资源解析模板集合。 */
        fun fromResources(context: Context): HumanDurationPatterns = HumanDurationPatterns(
            daysHours = context.getString(R.string.duration_days_hours_fmt),
            hoursMinutes = context.getString(R.string.duration_hours_minutes_fmt),
            hoursOnly = context.getString(R.string.duration_hours_fmt),
            minutesOnly = context.getString(R.string.duration_minutes_fmt),
            underMinute = context.getString(R.string.duration_under_minute),
        )

        /** 简体中文模板（测试基线用）。 */
        val zh: HumanDurationPatterns = HumanDurationPatterns(
            daysHours = "%1\$d 天 %2\$d 小时",
            hoursMinutes = "%1\$d 小时 %2\$d 分钟",
            hoursOnly = "%1\$d 小时",
            minutesOnly = "%1\$d 分钟",
            underMinute = "不足 1 分钟",
        )
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
