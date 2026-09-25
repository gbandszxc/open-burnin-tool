package com.github.gbandszxc.obt.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.domain.model.SoundSource
import com.github.gbandszxc.obt.playback.PlaybackState

/**
 * 方案/阶段/音源展示名解析：域与播放层只携带结构化身份（planId/阶段身份 stageId/音源枚举），
 * 显示文案由本文件按当前应用语言经资源统一解析，供 Compose UI 与前台服务通知共用。
 * 语言切换后无需等待播放状态重新发布，任何展示点即时得到新语言文案。
 */

/** 方案 id 形如 classic_120h / quick_8h / custom_48h（见 [com.github.gbandszxc.obt.domain.model.BurnPlans]）。 */
private val PLAN_ID_REGEX = Regex("^(classic|quick|custom)_(\\d+)h$")

private fun planKindOf(planId: String): Pair<String, Int>? {
    val match = PLAN_ID_REGEX.matchEntire(planId) ?: return null
    val hours = match.groupValues[2].toIntOrNull() ?: return null
    return match.groupValues[1] to hours
}

/** 音源展示名（枚举 → 当前语言资源文案）。 */
fun soundDisplayName(source: SoundSource, context: Context): String = context.getString(source.nameRes)

/** 音源展示名的 Compose 版（组合内经 stringResource 解析，随语言切换重组）。 */
@Composable
fun soundDisplayName(source: SoundSource): String = stringResource(source.nameRes)

/** 方案展示名（按 planId 解析；无法识别的 id 原样返回兜底）。 */
fun planDisplayName(planId: String, context: Context): String {
    val (kind, hours) = planKindOf(planId) ?: return planId
    return when (kind) {
        "classic" -> context.getString(R.string.plan_classic_name)
        "quick" -> context.getString(R.string.plan_quick_fmt, hours)
        else -> context.getString(R.string.plan_custom_fmt, hours)
    }
}

/**
 * 播放态的方案展示名：本地音乐快捷煲机按惯例在时长后追加曲目名，
 * 便于与播放同曲目的其他会话区分；其余方案按 planId 解析。
 */
fun planDisplayName(state: PlaybackState, context: Context): String {
    val (kind, hours) = planKindOf(state.planId) ?: return planDisplayName(state.planId, context)
    if (kind == "quick" && state.soundSource == SoundSource.LOCAL_TRACK) {
        return context.getString(
            R.string.plan_quick_local_fmt,
            hours,
            state.localTrackName ?: context.getString(R.string.sound_local_track),
        )
    }
    return planDisplayName(state.planId, context)
}

/**
 * 阶段展示名：本地音乐阶段显示当前曲目名（解析未就绪回退「本地音乐」，优先级在四阶段名之前）；
 * 快捷单阶段显示「快速 + 音源名」；标准/自定义四阶段按阶段固定身份
 * [PlaybackState.stageId] 取原版四阶段名——阶段名是阶段身份的语言资源映射，
 * 不随播放顺序（重排/注入）改变；[PlaybackState.phaseIndex] 只承担「阶段 i/N」的位置序号。
 * stageId 缺失（旧状态）或越界时回退方案 id 兜底。
 */
fun phaseDisplayName(state: PlaybackState, context: Context): String {
    val source = state.soundSource ?: return state.planId
    if (source == SoundSource.LOCAL_TRACK) {
        return state.localTrackName ?: context.getString(R.string.sound_local_track)
    }
    if (planKindOf(state.planId)?.first == "quick") {
        return context.getString(R.string.phase_quick_fmt, soundDisplayName(source, context))
    }
    val nameRes = when (state.stageId) {
        0 -> R.string.phase_warmup
        1 -> R.string.phase_activation
        2 -> R.string.phase_endurance
        3 -> R.string.phase_rotation
        else -> null
    }
    return nameRes?.let { context.getString(it) } ?: state.planId
}
