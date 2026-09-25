package com.github.gbandszxc.obt.ui.burnin

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.domain.model.BurnPlan
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.playback.BurnInUiState
import com.github.gbandszxc.obt.playback.BurnMode
import com.github.gbandszxc.obt.playback.FreeSoundSelection
import com.github.gbandszxc.obt.playback.PlaybackState
import com.github.gbandszxc.obt.playback.PlaybackStatus
import com.github.gbandszxc.obt.playback.PlanCard
import com.github.gbandszxc.obt.playback.formatBurnDuration
import com.github.gbandszxc.obt.ui.phaseDisplayName
import com.github.gbandszxc.obt.ui.planDisplayName
import com.github.gbandszxc.obt.ui.soundDisplayName
import java.util.concurrent.TimeUnit

/**
 * 煲机 Tab：未开始态（双路线配置，见 [IdleContent]）与进行/暂停态（进度环 + 计时 + 控制）。
 *
 * 状态收敛于 [BurnInViewModel] 暴露的 [BurnInUiState]（配置态）与 [PlaybackState]（播放态），
 * 本组件不持有业务状态，全部变更经回调上抛；播放中（非 IDLE）配置区整体被播放态替代，
 * 即「播放中禁用音效/时长/方案编辑」。
 */
@Composable
fun BurnInTab(
    uiState: BurnInUiState,
    playbackState: PlaybackState,
    totalCompletedSeconds: Long,
    onModeChange: (BurnMode) -> Unit,
    onPlanCardChange: (PlanCard) -> Unit,
    onPlanCustomHoursChange: (String) -> Unit,
    onStepPlanCustomHours: (Int) -> Unit,
    onStartPlan: (BurnPlan, Long?) -> Unit,
    onFreeSoundChange: (FreeSoundSelection) -> Unit,
    onFreePresetHoursChange: (Int) -> Unit,
    onFreeCustomHoursChange: (String) -> Unit,
    onStartFree: () -> Unit,
    onImportTrack: (Uri) -> Unit,
    onDeleteTrack: (LocalTrack) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playbackState.status == PlaybackStatus.IDLE) {
        IdleContent(
            uiState = uiState,
            totalCompletedSeconds = totalCompletedSeconds,
            onModeChange = onModeChange,
            onPlanCardChange = onPlanCardChange,
            onPlanCustomHoursChange = onPlanCustomHoursChange,
            onStepPlanCustomHours = onStepPlanCustomHours,
            onStartPlan = onStartPlan,
            onFreeSoundChange = onFreeSoundChange,
            onFreePresetHoursChange = onFreePresetHoursChange,
            onFreeCustomHoursChange = onFreeCustomHoursChange,
            onStartFree = onStartFree,
            onImportTrack = onImportTrack,
            onDeleteTrack = onDeleteTrack,
            modifier = modifier,
        )
    } else {
        ActiveContent(
            state = playbackState,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
            modifier = modifier,
        )
    }
}

/** 由 planId 只读重建方案（用于推导「阶段 x/4」）；无法识别的 id 返回 null。 */
private fun rebuildPlan(planId: String): BurnPlan? {
    val match = Regex("^(classic|quick|custom)_(\\d+)h$").matchEntire(planId) ?: return null
    val hours = match.groupValues[2].toIntOrNull() ?: return null
    return when (match.groupValues[1]) {
        "classic" -> if (hours * 3_600L == BurnPlans.CLASSIC_TOTAL_SECONDS) BurnPlans.CLASSIC else null
        "quick" -> BurnPlans.quick(hours)
        else -> BurnPlans.custom(hours)
    }
}

/** 播放态第二行文案：多阶段方案显示「方案名 · 阶段 x/y · 阶段名」，单阶段只显示方案名。 */
private fun phaseLineOf(state: PlaybackState, context: Context): String {
    val plan = rebuildPlan(state.planId)
    val planName = planDisplayName(state, context)
    if (plan == null || plan.phases.size <= 1) return planName
    return context.getString(
        R.string.phase_line_fmt,
        planName,
        state.phaseIndex + 1,
        plan.phases.size,
        phaseDisplayName(state, context),
    )
}

/**
 * 进行/暂停态：进度环 + 大号计时 + 剩余 + 状态行（实际音源）+ 暂停(继续)/结束控制。
 *
 * 「结束」确认框的时序语义：打开即暂停（onPause，用户停下了）、取消即恢复（onResume）、
 * 确认则结束（onStop）——确认框文案定格的已煲秒数与最终落库值因此天然一致。
 */
@Composable
private fun ActiveContent(
    state: PlaybackState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStopConfirm by remember { mutableStateOf(false) }
    // 结束确认框打开那一刻定格的已煲秒数：打开即暂停（见下），定格值即引擎暂停值，
    // 「已煲 X」文案与确认后实际落库的秒数天然一致（此前打开后计时继续走，
    // 文案与落库曾实测漂移 01:32 vs 01:44）
    var stopConfirmSeconds by remember { mutableStateOf(0L) }
    // 是否由打开确认框触发了自动暂停：取消关闭时仅此时才自动恢复，
    // 手动暂停后点「结束」（本就暂停、未打标记）取消时不能误恢复
    var pausedByStopConfirm by remember { mutableStateOf(false) }
    val paused = state.isPaused
    val crossDayTemplate = stringResource(R.string.duration_cross_day_fmt)
    val context = LocalContext.current
    val soundName = state.soundSource?.let { soundDisplayName(it, context) }
        ?: stringResource(R.string.sound_local_track)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        ProgressRing(
            progress = state.progressFraction.toFloat(),
            modifier = Modifier.size(272.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 跨天字符串更长，降字号保证单行放得下（tnum 保证逐秒跳动不位移）
                val crossDay = state.completedSeconds >= TimeUnit.DAYS.toSeconds(1)
                Text(
                    text = formatBurnDuration(state.completedSeconds, crossDayTemplate),
                    style = if (crossDay) {
                        MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp)
                    } else {
                        MaterialTheme.typography.displaySmall
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.remaining_fmt, formatBurnDuration(state.remainingSeconds, crossDayTemplate)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // 状态行展示实际播放音源（轮换判定、自由选择、本地音乐均如实反映）
        Text(
            text = stringResource(
                if (paused) R.string.status_paused_fmt else R.string.status_playing_fmt,
                soundName,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = if (paused) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = phaseLineOf(state, context),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (paused) {
                Button(onClick = onResume, modifier = Modifier.height(52.dp)) {
                    Icon24(Icons.Filled.PlayArrow)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_resume), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(onClick = onPause, modifier = Modifier.height(52.dp)) {
                    Icon24(Icons.Filled.Pause)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_pause), style = MaterialTheme.typography.titleMedium)
                }
            }
            OutlinedButton(
                onClick = {
                    // 打开确认框的同时定格已煲秒数（此刻值即最终落库基准）
                    stopConfirmSeconds = state.completedSeconds
                    // 打开即暂停（语义=用户停下了）：暂停值与定格文案一致，确认后落库不漂移；
                    // 打开时本就是暂停态（手动暂停后点结束）则不重复 pause、不打标记，
                    // 取消关闭时也不会把用户的手动暂停误自动恢复
                    if (!paused) {
                        onPause()
                        pausedByStopConfirm = true
                    }
                    showStopConfirm = true
                },
                modifier = Modifier.height(52.dp),
            ) {
                Icon24(Icons.Filled.Stop)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_stop), style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // 取消路径统一收口（「继续煲机」按钮 / 背衬点击 dismiss）：仅当暂停由打开本框引起时
    // 才恢复播放；恢复被音频焦点占用等原因拒绝时走 controller.resume() 既有失败路径，保持暂停
    val closeStopConfirm = {
        showStopConfirm = false
        if (pausedByStopConfirm) {
            pausedByStopConfirm = false
            onResume()
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = closeStopConfirm,
            title = { Text(stringResource(R.string.dialog_stop_title)) },
            text = {
                // 使用打开瞬间定格的秒数，与确认后落库值一致（不用实时 state，避免漂移）
                Text(
                    stringResource(
                        R.string.dialog_stop_body,
                        formatBurnDuration(stopConfirmSeconds, crossDayTemplate),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirm = false
                        // 会话即将被 stop() 终结（ActiveContent 随之离开组合），复位标记防残留
                        pausedByStopConfirm = false
                        onStop()
                    },
                ) { Text(stringResource(R.string.btn_stop)) }
            },
            dismissButton = {
                TextButton(onClick = closeStopConfirm) { Text(stringResource(R.string.btn_keep_burning)) }
            },
        )
    }
}

/** 统一 24dp 行内图标尺寸。 */
@Composable
private fun Icon24(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
    )
}
