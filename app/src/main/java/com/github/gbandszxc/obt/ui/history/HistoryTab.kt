package com.github.gbandszxc.obt.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import com.github.gbandszxc.obt.playback.BurnInViewModel
import com.github.gbandszxc.obt.playback.formatBurnDuration
import com.github.gbandszxc.obt.ui.HumanDurationPatterns
import com.github.gbandszxc.obt.ui.formatDurationHuman
import com.github.gbandszxc.obt.ui.planDisplayName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 触发追加下一页的提前量：可见项进入倒数第 N 项（含尾项提示）即预取。 */
private const val LOAD_MORE_VISIBLE_THRESHOLD = 3

/** 列表尾提示项的 key（与会话行的 id key 区分）。 */
private const val HISTORY_FOOTER_KEY = "history_footer"

/** 可从记录页续播的会话状态（进行中/已暂停；已完成与已放弃不可续）。 */
private val RESUMABLE_STATUSES = setOf(SessionStatus.RUNNING, SessionStatus.PAUSED)

/**
 * 记录 Tab：顶部小结（累计煲机 + 会话次数 + 清除入口）+ 分页会话列表；
 * 滚近列表末尾自动追加下一页，尾项给「加载中 / 共 N 条」提示；空态给引导文案。
 *
 * 历史续播：播放器空闲（[showResumeActions]）时，进行中/已暂停且可重建方案的会话行
 * 显示「继续」按钮，点击经 [onResumeSession] 续播该会话（跳转煲机页、暂停态起步）。
 * 0 进度的进行中/已暂停行同样显示——进度落库粒度为 60 秒（PlaybackController
 * PERSIST_EVERY_TICKS），起播后未满一分钟即退出的会话进度仍为 0，属正常状态。
 *
 * BurnInApp 已收集同一 Activity 级 [HistoryViewModel] 的已加载列表与累计时长传入；
 * 分页辅助状态（到底/加载中/总数）与清除动作经 viewModel(factory=...) 取同一单例
 * ViewModel（默认 key 按类命中同一实例），不会重复创建。
 */
@Composable
fun HistoryTab(
    sessions: List<BurnInSession>,
    totalCompletedSeconds: Long,
    modifier: Modifier = Modifier,
    onResumeSession: (BurnInSession) -> Unit = {},
    showResumeActions: Boolean = false,
) {
    val app = LocalContext.current.applicationContext as BurnInApplication
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(app))
    val endReached by viewModel.endReached.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val sessionCount by viewModel.sessionCount.collectAsStateWithLifecycle()

    if (sessions.isEmpty()) {
        EmptyHistory(modifier)
        return
    }

    // 滚动接近末尾（或尾项进入预取范围）时自动加载下一页；VM 侧对重复触发幂等
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleIndex >= info.totalItemsCount - LOAD_MORE_VISIBLE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, endReached, isLoadingMore) {
        if (nearEnd && !endReached && !isLoadingMore) {
            viewModel.loadNextPage()
        }
    }

    var showClearDialog by remember { mutableStateOf(false) }
    if (showClearDialog) {
        ClearConfirmDialog(
            onConfirm = {
                showClearDialog = false
                viewModel.clearAll()
            },
            onDismiss = { showClearDialog = false },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SummaryRow(
            totalCompletedSeconds = totalCompletedSeconds,
            sessionCount = sessionCount,
            onClearClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = 24.dp,
                vertical = 8.dp,
            ),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    showResumeButton = showResumeActions &&
                        session.status in RESUMABLE_STATUSES &&
                        BurnInViewModel.sessionResumableFromHistory(session),
                    onResumeClick = { onResumeSession(session) },
                )
            }
            item(key = HISTORY_FOOTER_KEY) {
                HistoryFooter(
                    isLoadingMore = isLoadingMore,
                    endReached = endReached,
                    totalCount = sessionCount,
                )
            }
        }
    }
}

/** 小结：两列数据并排 + 末尾「清除」入口，非大数字英雄区。 */
@Composable
private fun SummaryRow(
    totalCompletedSeconds: Long,
    sessionCount: Int,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.history_total_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDurationHuman(
                    totalCompletedSeconds,
                    HumanDurationPatterns.fromResources(LocalContext.current),
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Text(
                text = stringResource(R.string.history_sessions_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.history_session_count_fmt, sessionCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClearClick) {
            Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = stringResource(R.string.cd_clear_history),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 清除二次确认：明确「删全部记录 + 重置累计统计 + 不可恢复」。 */
@Composable
private fun ClearConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.history_clear_title)) },
        text = { Text(text = stringResource(R.string.history_clear_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.btn_clear), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.btn_cancel))
            }
        },
    )
}

/** 列表尾：追加中给小号转圈，到底给一行克制的总数提示；未到底时为不可见预取占位。 */
@Composable
private fun HistoryFooter(
    isLoadingMore: Boolean,
    endReached: Boolean,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.history_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            endReached -> Text(
                text = stringResource(R.string.history_total_count_fmt, totalCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 会话行方案 id 的显示口径（只推导 id 供文案回显，不构造方案，区别于续播重建的
 * [com.github.gbandszxc.obt.playback.BurnInViewModel.planForSession]）：
 * - 方案煲机会话（soundSourceId 为 null）：总时长等于标准方案（120 小时）→ classic_120h，
 *   其余一律 custom_{presetHours}h——方案煲机只产生标准/自定义四阶段方案，绝不产生
 *   quick 方案，不能按 [BurnPlans.forPresetHours] 的预设路由回显（否则方案煲机自定义
 *   8 小时的行会误显「快速煲机 8 小时」，与煲机页/通知的「自定义 8 小时」不一致）；
 * - 自由煲机会话（soundSourceId 非空）：一律 quick_{presetHours}h——自由煲机只有
 *   单阶段 quick 方案，presetHours 可为 1-999 任意小时，含非 QUICK_HOURS 的自定义小时。
 */
fun historyPlanIdFor(session: BurnInSession): String =
    if (session.soundSourceId != null) {
        "quick_${session.presetHours}h"
    } else if (session.presetHours * 3_600L == BurnPlans.CLASSIC_TOTAL_SECONDS) {
        BurnPlans.CLASSIC.id
    } else {
        "custom_${session.presetHours}h"
    }

/**
 * 单条会话：日期时间 + 状态，方案与计划时长（自由煲机追加所用音效），实际已煲。
 *
 * 历史续播：[showResumeButton] 为真时在状态文本左侧显示「继续」播放三角按钮
 * （48dp 触达、24dp 图标、primary 着色），点击经 [onResumeClick] 上抛续播；
 * 已煲 00:00 的进行中/已暂停行同样显示（进度落库粒度 60 秒，见 [HistoryTab] KDoc）。
 */
@Composable
private fun SessionRow(
    session: BurnInSession,
    showResumeButton: Boolean,
    onResumeClick: () -> Unit,
) {
    val statusColor = when (session.status) {
        SessionStatus.RUNNING, SessionStatus.PAUSED -> MaterialTheme.colorScheme.primary
        SessionStatus.COMPLETED, SessionStatus.ABANDONED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val context = LocalContext.current
    val crossDayTemplate = stringResource(R.string.duration_cross_day_fmt)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatSessionTime(session.startedAt, context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (showResumeButton) {
                IconButton(onClick = onResumeClick) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.cd_history_resume_session),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = statusLabel(session.status),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }
        Spacer(Modifier.height(2.dp))
        // 方案名按会话行显示口径（historyPlanIdFor）解析，与煲机页/通知的文案一致；
        // 自由煲机会话（soundSourceId 非空）在方案行末尾追加「 · 音效名」。
        val planName = planDisplayName(historyPlanIdFor(session), context)
        val planned = formatBurnDuration(session.plannedSeconds, crossDayTemplate)
        val soundName = sessionSoundLabel(session)
        Text(
            text = if (soundName != null) {
                stringResource(R.string.history_plan_line_sound_fmt, planName, planned, soundName)
            } else {
                stringResource(R.string.history_plan_line_fmt, planName, planned)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(
                R.string.history_burned_fmt,
                formatBurnDuration(session.completedSeconds, crossDayTemplate),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 空态：图标 + 一句说明，不堆插画。 */
@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.history_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.history_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 会话状态展示名（UI 层按资源解析，随应用语言切换）。 */
@Composable
private fun statusLabel(status: SessionStatus): String = stringResource(
    when (status) {
        SessionStatus.RUNNING -> R.string.status_running
        SessionStatus.PAUSED -> R.string.status_paused
        SessionStatus.COMPLETED -> R.string.status_completed
        SessionStatus.ABANDONED -> R.string.status_abandoned
    },
)

/**
 * 会话行音效回显名：仅自由煲机会话（soundSourceId 非空）有值——
 * 内置合成音源取其本地化名；本地音乐取会话开始时的曲目名快照（曲目之后可能被删，
 * 快照缺失时回退「本地音乐」占位）。方案煲机与迁移前旧数据（null/未知编号）返回 null，
 * 行内不追加音效。展示名随应用语言即时解析（见 [SoundSource.fromLegacySoundId]）。
 */
@Composable
private fun sessionSoundLabel(session: BurnInSession): String? {
    val source = SoundSource.fromLegacySoundId(session.soundSourceId) ?: return null
    return if (source == SoundSource.LOCAL_TRACK) {
        session.soundLabel ?: stringResource(R.string.sound_local_track)
    } else {
        stringResource(source.nameRes)
    }
}

/** 会话开始时间：pattern 随应用语言（资源 history_time_pattern，如 zh「M月d日 HH:mm」/ en「MMM d, HH:mm」）。 */
private fun formatSessionTime(epochMillis: Long, context: Context): String =
    DateTimeFormatter.ofPattern(context.getString(R.string.history_time_pattern))
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
