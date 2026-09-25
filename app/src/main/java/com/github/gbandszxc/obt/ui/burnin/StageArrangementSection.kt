package com.github.gbandszxc.obt.ui.burnin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.CheckCircle as CheckCircleOutlined
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import com.github.gbandszxc.obt.playback.BurnInUiState
import com.github.gbandszxc.obt.ui.InfoAction
import com.github.gbandszxc.obt.ui.soundDisplayName
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 编排区卡片圆角：与方案卡（PlanCard）同一档。 */
private val ARRANGEMENT_CARD_SHAPE = RoundedCornerShape(16.dp)

/** 阶段行固定高度：四行等高是拖拽换位与让位预览的几何前提。 */
private val STAGE_ROW_HEIGHT = 56.dp

/** 阶段行 / 稳定音乐清单行的圆角：与输入框（CompactNumberField）同一档。 */
private val STAGE_ROW_SHAPE = RoundedCornerShape(12.dp)

/** 行尾响度徽标圆角。 */
private val LOUDNESS_BADGE_SHAPE = RoundedCornerShape(8.dp)

/** 拖拽手柄触控盒（48dp，M3 最小触达目标）。 */
private val DRAG_HANDLE_TOUCH = 48.dp

/** 拖拽中行的抬升阴影（dp）与轻微缩放。 */
private val DRAG_LIFT_SHADOW = 8.dp
private const val DRAG_LIFT_SCALE = 1.02f

/** 阶段身份（与域层 BurnPlans 的 stageId 口径一致，展示层按身份取资源，同 PlanDisplay）。 */
private const val STAGE_GENTLE = 0
private const val STAGE_ADAPT = 1
private const val STAGE_STEADY = 2
private const val STAGE_ALTERNATE = 3

/** 响度百分比合法范围（域层比例 (0, 1] 的整数百分比表达）。 */
private val LOUDNESS_PERCENT_RANGE = 1..100

/** 响度输入框最大位数（100 即 3 位，与 BurnInUiState.MAX_INPUT_DIGITS 同口径）。 */
private const val LOUDNESS_INPUT_MAX_DIGITS = 3

/**
 * 阶段行拖拽的瞬态状态（remember 存活于 [StageRows]）：
 * [index] 为被拖行下标（-1 = 未在拖拽），[offsetPx] 为跟手位移（已按行高与列表边界收敛）。
 */
private class RowDragState {
    var index by mutableStateOf(-1)
    var offsetPx by mutableStateOf(0f)
}

/**
 * 阶段编排配置区（方案煲机两张方案卡下方）：作用于经典与自定义四阶段方案。
 *
 * - 四行阶段行按 [BurnInUiState.stageOrder] 渲染（编号 = 第 1–4 位），长按手柄拖动整行
 *   重排，松手经 [onStageOrderChange] 上抛；行尾响度徽标显示覆盖值（缺省该阶段默认比例），
 *   点击弹编辑对话框经 [onStageGainChange] 上抛（比例 / 清除覆盖）；
 * - 稳定阶段（stageId = 2）行下附音乐子区：粉噪恒定 / 音乐二档切换（[onSteadyMusicEnabledChange]）、
 *   有序歌单勾选（[onToggleSteadyTrack]，勾选顺序 = 播放顺序）与「导入本地音乐…」入口
 *   （[onImportSteadyTrack]，导入中复用 importing 态）；
 * - 删除曲目走既有移除确认（[TrackRemoveConfirmDialog]），确认后经 [onDeleteTrack] 上抛。
 *
 * 全部状态来自 [uiState]，事件全部经回调上抛；持久化与派生口径在 ViewModel/DataStore 侧收敛。
 */
@Composable
internal fun StageArrangementSection(
    uiState: BurnInUiState,
    onStageOrderChange: (List<Int>) -> Unit,
    onStageGainChange: (Int, Double?) -> Unit,
    onSteadyMusicEnabledChange: (Boolean) -> Unit,
    onToggleSteadyTrack: (Long) -> Unit,
    onImportSteadyTrack: (Uri) -> Unit,
    onDeleteTrack: (LocalTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 纯交互状态留在 UI 层：正在编辑响度的阶段 id / 待确认删除的曲目
    var editingGainStageId by remember { mutableStateOf<Int?>(null) }
    var pendingDeleteTrack by remember { mutableStateOf<LocalTrack?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ARRANGEMENT_CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, ARRANGEMENT_CARD_SHAPE)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ARRANGEMENT_CARD_SHAPE)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(width = 3.dp, height = 20.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            Text(
                text = stringResource(R.string.stage_arrangement_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            InfoAction(
                title = stringResource(R.string.stage_arrangement_title),
                description = stringResource(R.string.stage_arrangement_info_body),
            )
        }
        Spacer(Modifier.height(14.dp))
        StageRows(
            uiState = uiState,
            onStageOrderChange = onStageOrderChange,
            onEditGain = { editingGainStageId = it },
            onSteadyMusicEnabledChange = onSteadyMusicEnabledChange,
            onToggleSteadyTrack = onToggleSteadyTrack,
            onImportSteadyTrack = onImportSteadyTrack,
            onDeleteRequest = { pendingDeleteTrack = it },
        )
    }

    editingGainStageId?.let { stageId ->
        LoudnessEditDialog(
            stageId = stageId,
            stageName = stageDisplayName(stageId),
            initialPercent = stageGainPercent(uiState, stageId),
            onConfirm = { percent ->
                editingGainStageId = null
                onStageGainChange(stageId, percent / 100.0)
            },
            onResetDefault = {
                editingGainStageId = null
                onStageGainChange(stageId, null)
            },
            onDismiss = { editingGainStageId = null },
        )
    }

    pendingDeleteTrack?.let { track ->
        TrackRemoveConfirmDialog(
            track = track,
            onConfirm = {
                pendingDeleteTrack = null
                onDeleteTrack(track)
            },
            onDismiss = { pendingDeleteTrack = null },
        )
    }
}

// ------------------------------------------------------------------
// 阶段行与拖拽排序
// ------------------------------------------------------------------

/**
 * 四行阶段行（4 行定长 Column）：拖拽中维护本地 [RowDragState] 与展示顺序 [displayOrder]，
 * 拖拽期间被让位行按整行高平移做落点预览（ease-out 追随），被拖行位移直接跟手并视觉抬升；
 * 松手提交新顺序并做一次残余位移的收尾回位动画。
 */
@Composable
private fun StageRows(
    uiState: BurnInUiState,
    onStageOrderChange: (List<Int>) -> Unit,
    onEditGain: (Int) -> Unit,
    onSteadyMusicEnabledChange: (Boolean) -> Unit,
    onToggleSteadyTrack: (Long) -> Unit,
    onImportSteadyTrack: (Uri) -> Unit,
    onDeleteRequest: (LocalTrack) -> Unit,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { STAGE_ROW_HEIGHT.toPx() }
    val liftShadowPx = with(density) { DRAG_LIFT_SHADOW.toPx() }
    val scope = rememberCoroutineScope()
    // 收尾回位与让位预览共用同一 ease-out 口径（系统动画关闭时 snap，见 easeOutSpec）
    val settleSpec = easeOutSpec()
    val settleOffset = remember { Animatable(0f) }

    // 拖拽期间的展示顺序：拖起时与 uiState.stageOrder 一致，松手先本地提交、再等 DataStore 回流
    var displayOrder by remember { mutableStateOf(uiState.stageOrder) }
    var settlePosition by remember { mutableStateOf(-1) }
    val drag = remember { RowDragState() }

    // 非拖拽期间跟随外部状态回流：提交后持久化异步回流的间隙由本地先行渲染，避免视觉回跳
    LaunchedEffect(uiState.stageOrder) {
        if (drag.index < 0) displayOrder = uiState.stageOrder
    }

    fun targetIndex(from: Int): Int =
        (from + (drag.offsetPx / rowHeightPx).roundToInt()).coerceIn(0, displayOrder.lastIndex)

    /** 收尾：被拖行（或取消回弹的行）从残余位移 ease-out 滑回整行位，避免松手瞬跳。 */
    fun settle(to: Int, residualPx: Float) {
        settlePosition = to
        scope.launch {
            settleOffset.snapTo(residualPx)
            settleOffset.animateTo(0f, settleSpec)
            if (settlePosition == to) settlePosition = -1
        }
    }

    fun finishDrag(commit: Boolean) {
        val from = drag.index
        if (from < 0) return
        val to = targetIndex(from)
        val residual = from * rowHeightPx + drag.offsetPx - to * rowHeightPx
        drag.index = -1
        drag.offsetPx = 0f
        if (commit && to != from) {
            val next = displayOrder.toMutableList()
            next.add(to, next.removeAt(from))
            displayOrder = next
            onStageOrderChange(next)
        }
        settle(to, residual)
    }

    Column(Modifier.fillMaxWidth()) {
        displayOrder.forEachIndexed { position, stageId ->
            // key(stageId)：让位预览的动画状态跟随阶段身份而非列表槽位，
            // 提交换位后各阶段从自己的旧位移平滑归位，不会出现槽位状态串位的二次滑动
            key(stageId) {
                val isDragging = drag.index == position
                val dragActive = drag.index >= 0
                val dragTarget = if (dragActive) targetIndex(drag.index) else -1
                // 让位预览：位于拖拽起点与当前落点之间的行按整行高平移让位
                val previewShift = when {
                    !dragActive -> 0f
                    position > drag.index && position <= dragTarget -> -rowHeightPx
                    position < drag.index && position >= dragTarget -> rowHeightPx
                    else -> 0f
                }
                val animatedShift by animateFloatAsState(
                    targetValue = previewShift,
                    animationSpec = settleSpec,
                    label = "stageArrangementShift",
                )
                val liftScale by animateFloatAsState(
                    targetValue = if (isDragging) DRAG_LIFT_SCALE else 1f,
                    animationSpec = settleSpec,
                    label = "stageArrangementLiftScale",
                )
                val liftShadow by animateFloatAsState(
                    targetValue = if (isDragging) liftShadowPx else 0f,
                    animationSpec = settleSpec,
                    label = "stageArrangementLiftShadow",
                )
                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = when {
                                isDragging -> drag.offsetPx
                                !dragActive && position == settlePosition -> settleOffset.value
                                else -> animatedShift
                            }
                            scaleX = liftScale
                            scaleY = liftScale
                            shape = STAGE_ROW_SHAPE
                            shadowElevation = liftShadow
                        },
                ) {
                    StageRowContent(
                        uiState = uiState,
                        stageId = stageId,
                        position = position,
                        lastIndex = displayOrder.lastIndex,
                        rowHeightPx = rowHeightPx,
                        drag = drag,
                        onGainClick = { onEditGain(stageId) },
                        onDragFinished = ::finishDrag,
                    )
                    // 稳定阶段音乐子区跟随该阶段行一起拖拽/让位（同组渲染）
                    if (stageId == STAGE_STEADY) {
                        SteadyMusicSection(
                            uiState = uiState,
                            onSteadyMusicEnabledChange = onSteadyMusicEnabledChange,
                            onToggleSteadyTrack = onToggleSteadyTrack,
                            onImportSteadyTrack = onImportSteadyTrack,
                            onDeleteRequest = onDeleteRequest,
                        )
                    }
                }
            }
            if (position < displayOrder.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 单行阶段行：拖拽手柄（长按拖动，手势只作用于手柄触控盒）+ 位次与阶段名 +
 * 音源摘要 + 行尾响度徽标（点击弹编辑对话框）。
 */
@Composable
private fun StageRowContent(
    uiState: BurnInUiState,
    stageId: Int,
    position: Int,
    lastIndex: Int,
    rowHeightPx: Float,
    drag: RowDragState,
    onGainClick: () -> Unit,
    onDragFinished: (Boolean) -> Unit,
) {
    val stageName = stageDisplayName(stageId)
    val percent = stageGainPercent(uiState, stageId)
    // 手势回调闭包只在 pointerInput(Unit) 首次组合时捕获，随换位变化的值须经 rememberUpdatedState 读取
    val gesturePosition by rememberUpdatedState(position)
    val gestureLastIndex by rememberUpdatedState(lastIndex)
    val gestureRowHeightPx by rememberUpdatedState(rowHeightPx)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(STAGE_ROW_HEIGHT)
            .background(MaterialTheme.colorScheme.surface, STAGE_ROW_SHAPE)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(DRAG_HANDLE_TOUCH)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            if (drag.index < 0) {
                                drag.offsetPx = 0f
                                drag.index = gesturePosition
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val from = drag.index
                            if (from >= 0) {
                                val heightPx = gestureRowHeightPx
                                drag.offsetPx = (drag.offsetPx + dragAmount.y)
                                    .coerceIn(-from * heightPx, (gestureLastIndex - from) * heightPx)
                            }
                        },
                        onDragEnd = { onDragFinished(true) },
                        onDragCancel = { onDragFinished(false) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.cd_stage_drag_handle, stageName),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.stage_position_fmt, position + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stageName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stageSummary(stageId, uiState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .clip(LOUDNESS_BADGE_SHAPE)
                .border(1.dp, MaterialTheme.colorScheme.outline, LOUDNESS_BADGE_SHAPE)
                .clickable(onClickLabel = stringResource(R.string.cd_stage_loudness, stageName)) {
                    onGainClick()
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// ------------------------------------------------------------------
// 稳定阶段音乐子区
// ------------------------------------------------------------------

/**
 * 稳定阶段音乐子区（只渲染在 stageId = 2 行下方）：粉噪恒定 / 音乐二档切换 +
 * 有序歌单清单（勾选顺序 = 播放顺序）+「导入本地音乐…」入口。
 * 选「音乐」才展开清单（animateContentSize）；空清单或全未勾选时显示回退粉噪的提示行。
 */
@Composable
private fun SteadyMusicSection(
    uiState: BurnInUiState,
    onSteadyMusicEnabledChange: (Boolean) -> Unit,
    onToggleSteadyTrack: (Long) -> Unit,
    onImportSteadyTrack: (Uri) -> Unit,
    onDeleteRequest: (LocalTrack) -> Unit,
) {
    // SAF launcher 留在 UI 层（依赖 Activity Result API），与自由煲机导入入口同一形态
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportSteadyTrack(uri)
    }
    Column(Modifier.padding(start = 6.dp, end = 2.dp, bottom = 6.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !uiState.steadyMusicEnabled,
                onClick = { onSteadyMusicEnabledChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {},
            ) {
                Text(
                    stringResource(R.string.steady_mode_noise),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SegmentedButton(
                selected = uiState.steadyMusicEnabled,
                onClick = { onSteadyMusicEnabledChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {},
            ) {
                Text(
                    stringResource(R.string.steady_mode_music),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.animateContentSize()) {
            if (!uiState.steadyMusicEnabled) return@Column
            Spacer(Modifier.height(10.dp))
            if (uiState.effectiveSteadyTrackIds.isEmpty()) {
                Text(
                    text = stringResource(R.string.steady_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            uiState.tracks.forEach { track ->
                SteadyTrackRow(
                    track = track,
                    order = uiState.steadyTrackIds.indexOf(track.id),
                    onToggle = { onToggleSteadyTrack(track.id) },
                    onDeleteRequest = { onDeleteRequest(track) },
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(STAGE_ROW_SHAPE)
                    .clickable(enabled = !uiState.importing) {
                        importLauncher.launch(arrayOf("audio/*"))
                    }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.item_importing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.item_import_music),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 稳定阶段歌单的曲目行：点击整行勾选/取消勾选（勾选显示主色对钩与播放顺序序号徽标），
 * 行尾删除图标上抛确认请求（确认弹窗由调用方持有）。
 *
 * @param order 在生效歌单中的序号（0 起），-1 = 未勾选。
 */
@Composable
private fun SteadyTrackRow(
    track: LocalTrack,
    order: Int,
    onToggle: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val checked = order >= 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(STAGE_ROW_SHAPE)
            .clickable(onClickLabel = stringResource(R.string.cd_steady_toggle_track, track.displayName)) {
                onToggle()
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (checked) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircleOutlined,
            contentDescription = null,
            tint = if (checked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        if (checked) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${order + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = track.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDeleteRequest, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.cd_remove_track, track.displayName),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ------------------------------------------------------------------
// 弹窗
// ------------------------------------------------------------------

/**
 * 响度编辑对话框：标题含阶段名；1–100 整数输入（非法行内报错并禁用确定），
 * 确定按百分比上抛覆盖值，「恢复默认」上抛 null 清除该阶段覆盖。
 */
@Composable
private fun LoudnessEditDialog(
    stageId: Int,
    stageName: String,
    initialPercent: Int,
    onConfirm: (Int) -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开时刻的生效值作为初值；remember(stageId) 保证换阶段重开时重置输入
    var input by remember(stageId) { mutableStateOf(initialPercent.toString()) }
    val parsed = input.trim().toIntOrNull()
    val isValid = parsed != null && parsed in LOUDNESS_PERCENT_RANGE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.loudness_dialog_title_fmt, stageName)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.loudness_input_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CompactNumberField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(LOUDNESS_INPUT_MAX_DIGITS) },
                    suffixText = stringResource(R.string.unit_percent),
                    isError = !isValid,
                    onClickLabel = stringResource(R.string.cd_loudness_input),
                    modifier = Modifier.width(160.dp),
                )
                if (!isValid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.loudness_range_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirm(parsed ?: return@TextButton) },
            ) { Text(stringResource(R.string.btn_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onResetDefault) { Text(stringResource(R.string.loudness_reset_default)) }
        },
    )
}

/**
 * 本地音乐移除确认弹窗：文案复用 dialog_remove_track_*（只删应用内副本与导入记录，
 * 原始音频文件不受影响），确认动作经 [onConfirm] 上抛，删除由调用方执行。
 */
@Composable
internal fun TrackRemoveConfirmDialog(
    track: LocalTrack,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_remove_track_title)) },
        text = { Text(stringResource(R.string.dialog_remove_track_body, track.displayName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.btn_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

// ------------------------------------------------------------------
// 展示解析（stageId → 资源/数值）
// ------------------------------------------------------------------

/** 阶段身份 → 展示名（沿用既有四阶段名资源键，与 PlanDisplay 同口径）。 */
@Composable
private fun stageDisplayName(stageId: Int): String = stringResource(
    when (stageId) {
        STAGE_GENTLE -> R.string.phase_warmup
        STAGE_ADAPT -> R.string.phase_activation
        STAGE_STEADY -> R.string.phase_endurance
        else -> R.string.phase_rotation
    },
)

/**
 * 阶段行次要摘要：舒缓/适应显示音源名、轮换显示白/粉噪交替格式；
 * 稳定行按生效歌单显示「音乐 · N 首」，否则粉红噪音。
 */
@Composable
private fun stageSummary(stageId: Int, uiState: BurnInUiState): String = when (stageId) {
    STAGE_STEADY ->
        if (uiState.steadyMusicEffective) {
            stringResource(R.string.steady_source_music_fmt, uiState.effectiveSteadyTrackIds.size)
        } else {
            soundDisplayName(SoundSource.PINK_NOISE)
        }
    STAGE_ALTERNATE -> stringResource(
        R.string.stage_source_alternate_fmt,
        soundDisplayName(SoundSource.WHITE_NOISE),
        soundDisplayName(SoundSource.PINK_NOISE),
    )
    STAGE_ADAPT -> soundDisplayName(SoundSource.PINK_NOISE)
    else -> soundDisplayName(SoundSource.WHITE_NOISE)
}

/** 行尾响度徽标的整数百分比：覆盖值优先，缺省回落该阶段默认比例（1/5、1/3、7/15、3/5）。 */
private fun stageGainPercent(uiState: BurnInUiState, stageId: Int): Int {
    val ratio = uiState.stageGains[stageId]
        ?: BurnPlans.CLASSIC_PHASES.first { it.stageId == stageId }.volumeRatio
    return (ratio * 100.0).roundToInt().coerceIn(1, 100)
}
