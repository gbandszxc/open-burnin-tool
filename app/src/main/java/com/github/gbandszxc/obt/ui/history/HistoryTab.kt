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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.playback.formatBurnDuration
import com.github.gbandszxc.obt.ui.formatDurationHuman
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 触发追加下一页的提前量：可见项进入倒数第 N 项（含尾项提示）即预取。 */
private const val LOAD_MORE_VISIBLE_THRESHOLD = 3

/** 列表尾提示项的 key（与会话行的 id key 区分）。 */
private const val HISTORY_FOOTER_KEY = "history_footer"

/**
 * 记录 Tab：顶部小结（累计煲机 + 会话次数 + 清除入口）+ 分页会话列表；
 * 滚近列表末尾自动追加下一页，尾项给「加载中 / 共 N 条」提示；空态给引导文案。
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
                SessionRow(session)
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
                text = "累计煲机",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDurationHuman(totalCompletedSeconds),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Text(
                text = "会话次数",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$sessionCount 次",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClearClick) {
            Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = "清除全部煲机记录",
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
        title = { Text(text = "清除全部煲机记录？") },
        text = { Text(text = "将删除全部煲机记录并重置累计统计，此操作不可恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "清除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
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
                    text = "正在加载…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            endReached -> Text(
                text = "共 $totalCount 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单条会话：日期时间 + 状态，方案与计划时长，实际已煲。 */
@Composable
private fun SessionRow(session: BurnInSession) {
    val statusColor = when (session.status) {
        SessionStatus.RUNNING, SessionStatus.PAUSED -> MaterialTheme.colorScheme.primary
        SessionStatus.COMPLETED, SessionStatus.ABANDONED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatSessionTime(session.startedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = statusLabel(session.status),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${BurnPlans.forPresetHours(session.presetHours).name}" +
                " · 计划 ${formatBurnDuration(session.plannedSeconds)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "已煲 ${formatBurnDuration(session.completedSeconds)}",
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
            text = "还没有煲机记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "去「煲机」页选择时长，开始第一次煲机",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.RUNNING -> "进行中"
    SessionStatus.PAUSED -> "已暂停"
    SessionStatus.COMPLETED -> "已完成"
    SessionStatus.ABANDONED -> "已结束"
}

private fun formatSessionTime(epochMillis: Long): String =
    SESSION_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private val SESSION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
