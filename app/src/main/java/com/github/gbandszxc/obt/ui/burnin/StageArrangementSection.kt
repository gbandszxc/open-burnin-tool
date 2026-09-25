package com.github.gbandszxc.obt.ui.burnin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** 稳定行编辑图标按钮触控尺寸（40dp，图标 20dp，满足 ≥40dp 触达目标）。 */
private val STAGE_EDIT_BUTTON_SIZE = 40.dp

/** 稳定播放内容弹窗里歌单清单的最大高度：超出纵向滚动，避免弹窗无限撑高。 */
private val STEADY_DIALOG_LIST_MAX_HEIGHT = 320.dp

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

/** 响度步进量（±5，与输入框手动输入互为补充；到边界由按钮禁用兜底）。 */
private const val LOUDNESS_STEP_PERCENT = 5

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
 * - 四行阶段行按 [BurnInUiState.stageOrder] 渲染（编号 = 第 1–4 位），四行形态统一，
 *   长按手柄拖动整行重排，松手经 [onStageOrderChange] 上抛；行尾响度徽标显示覆盖值
 *   （缺省该阶段默认比例），点击弹编辑对话框经 [onStageGainChange] 上抛（比例 / 清除覆盖）；
 * - 稳定阶段（stageId = 2）行尾响度徽标旁附编辑图标按钮，点开「稳定阶段播放内容」弹窗：
 *   粉噪恒定 / 音乐二档切换（[onSteadyMusicEnabledChange]）、有序歌单勾选
 *   （[onToggleSteadyTrack]，勾选顺序 = 播放顺序）与「导入本地音乐…」入口
 *   （[onImportSteadyTrack]，导入中复用 importing 态）；弹窗内变更全部即时生效；
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
    // 纯交互状态留在 UI 层：正在编辑响度的阶段 id / 稳定播放内容弹窗开关。
    // 用 rememberSaveable 持有，旋转重建后弹窗不丢（歌单内容本身由 DataStore 回流，天然保真）
    var editingGainStageId by rememberSaveable { mutableStateOf<Int?>(null) }
    var steadyEditOpen by rememberSaveable { mutableStateOf(false) }

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
            onEditSteady = { steadyEditOpen = true },
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

    if (steadyEditOpen) {
        SteadyEditDialog(
            uiState = uiState,
            onSteadyMusicEnabledChange = onSteadyMusicEnabledChange,
            onToggleSteadyTrack = onToggleSteadyTrack,
            onImportSteadyTrack = onImportSteadyTrack,
            onDeleteTrack = onDeleteTrack,
            onDismiss = { steadyEditOpen = false },
        )
    }
}

// ------------------------------------------------------------------
// 阶段行与拖拽排序
// ------------------------------------------------------------------

/**
 * 四行阶段行（4 行定长 Column）：拖拽中维护本地 [RowDragState] 与展示顺序 [displayOrder]，
 * 拖拽期间被让位行按整行高平移做落点预览（ease-out 追随），被拖行位移直接跟手并视觉抬升；
 * 松手提交新顺序并做一次残余位移的收尾回位动画。四行形态统一，不再附行内子区。
 */
@Composable
private fun StageRows(
    uiState: BurnInUiState,
    onStageOrderChange: (List<Int>) -> Unit,
    onEditGain: (Int) -> Unit,
    onEditSteady: () -> Unit,
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
                        onEditSteady = onEditSteady,
                        onDragFinished = ::finishDrag,
                    )
                }
            }
            if (position < displayOrder.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 单行阶段行：拖拽手柄（长按拖动，手势只作用于手柄触控盒）+ 位次与阶段名 +
 * 音源摘要 + 行尾响度徽标（点击弹编辑对话框）；稳定阶段行在徽标旁再附编辑图标按钮
 * （点开「稳定阶段播放内容」弹窗），其余行四行形态一致。
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
    onEditSteady: () -> Unit,
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
        if (stageId == STAGE_STEADY) {
            // 稳定行专属编辑入口：打开「稳定阶段播放内容」弹窗（模式切换 + 歌单管理收进弹窗）
            IconButton(
                onClick = onEditSteady,
                modifier = Modifier.size(STAGE_EDIT_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.cd_stage_edit_steady),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            // 非稳定行用等宽占位，保证四行行尾元素右缘对齐
            Spacer(Modifier.width(STAGE_EDIT_BUTTON_SIZE))
        }
    }
}

// ------------------------------------------------------------------
// 稳定阶段播放内容弹窗
// ------------------------------------------------------------------

/**
 * 稳定阶段播放内容弹窗（stageId = 2 行编辑入口，原行内子区整体收进弹窗）：
 * 粉噪恒定 / 音乐二档 SegmentedButton（同 ModeSwitchRow 形态，`icon = {}` 去对钩），
 * 选「音乐」展开有序歌单（勾选顺序 = 播放顺序 + 行删除 + 导入入口 + 空清单提示）。
 *
 * 即时生效口径：所有变更经既有回调直接上抛（DataStore 单一数据源回流 [BurnInUiState]），
 * 弹窗不另设确认步骤，只有「关闭」动作。SAF launcher 与待删曲目确认留在弹窗组合内
 * （前者依赖 Activity Result API，后者是纯交互状态）；待删曲目以 id 经 rememberSaveable
 * 持有，旋转重建后确认态不丢（展示时再从曲目列表解析回 [LocalTrack]）。
 */
@Composable
private fun SteadyEditDialog(
    uiState: BurnInUiState,
    onSteadyMusicEnabledChange: (Boolean) -> Unit,
    onToggleSteadyTrack: (Long) -> Unit,
    onImportSteadyTrack: (Uri) -> Unit,
    onDeleteTrack: (LocalTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    // SAF launcher 留在 UI 层（依赖 Activity Result API），与自由煲机导入入口同一形态
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportSteadyTrack(uri)
    }
    // 待删除曲目 id（null = 无待确认）：存 id 而非对象，Long 可直接参与状态保存
    var pendingDeleteTrackId by rememberSaveable { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.steady_edit_dialog_title)) },
        text = {
            Column {
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
                if (uiState.steadyMusicEnabled) {
                    Spacer(Modifier.height(12.dp))
                    // 歌单清单限高纵向滚动：曲目多时不撑破弹窗（同音效下拉 380dp 的克制口径）
                    Column(
                        modifier = Modifier
                            .heightIn(max = STEADY_DIALOG_LIST_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                    ) {
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
                                onDeleteRequest = { pendingDeleteTrackId = track.id },
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        SteadyImportRow(
                            importing = uiState.importing,
                            onImportClick = { importLauncher.launch(arrayOf("audio/*")) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        },
    )

    pendingDeleteTrackId?.let { id ->
        uiState.tracks.firstOrNull { it.id == id }?.let { track ->
            TrackRemoveConfirmDialog(
                track = track,
                onConfirm = {
                    pendingDeleteTrackId = null
                    onDeleteTrack(track)
                },
                onDismiss = { pendingDeleteTrackId = null },
            )
        }
    }
}

/** 弹窗内「导入本地音乐…」行：SAF 入口（`primary` 文字），导入中 16dp 进度圈 + 禁用重复导入。 */
@Composable
private fun SteadyImportRow(importing: Boolean, onImportClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(STAGE_ROW_SHAPE)
            .clickable(enabled = !importing, onClick = onImportClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (importing) {
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
 * 响度编辑对话框：标题含阶段名；与「自定义四阶段总时长」同款步进形态——40dp 圆形 −/+
 * 按钮夹 160×48dp 数字输入框（后缀 %，见 [StepperIconButton]/[CompactNumberField]）。
 *
 * 步进口径与 ViewModel 的 stepPlanCustomHours 一致：草稿值非法时先回到最近的合法值
 * （越界收敛到 1/100 边界、无数字回落打开时的生效值）再 ±[LOUDNESS_STEP_PERCENT]，
 * 结果恒在 1–100 内，到边界对应按钮禁用；手动输入仍走 1–100 行内校验（非法报错并
 * 禁用确定）。步进与输入都只改对话框内草稿，确定才经 [onConfirm] 上抛持久化，
 * 「恢复默认」经 [onResetDefault] 清除该阶段覆盖。−/+ 步进做一次约 160ms 的数值淡入
 * 反馈（系统动画关闭时直接跳变，手动输入不打断打字节奏不触发）。
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
    // 打开时刻的生效值作为初值兼步进回落基准；rememberSaveable(stageId) 兼顾换阶段重开重置与旋转保持
    var input by rememberSaveable(stageId) { mutableStateOf(initialPercent.toString()) }
    val parsed = input.trim().toIntOrNull()
    val isValid = parsed != null && parsed in LOUDNESS_PERCENT_RANGE
    // 步进基准：合法值用现值、越界收敛到边界、无数字回落初值（与 stepPlanCustomHours 同口径）
    val stepBase = parsed?.coerceIn(LOUDNESS_PERCENT_RANGE) ?: initialPercent
    // 步进计数：仅 −/+ 触发淡入
    var stepTick by remember { mutableStateOf(0) }
    val numberAlpha = remember { Animatable(1f) }
    val animationsEnabled = rememberAnimationsEnabled()
    LaunchedEffect(stepTick) {
        if (stepTick > 0) {
            if (animationsEnabled) {
                numberAlpha.snapTo(0.4f)
                numberAlpha.animateTo(1f, tween(durationMillis = 160, easing = EaseOutCubic))
            } else {
                numberAlpha.snapTo(1f)
            }
        }
    }

    /** 步进：先落回最近合法值再 ±5，结果恒收敛回 1–100（写入草稿，确定才持久化）。 */
    fun step(direction: Int) {
        stepTick += 1
        input = (stepBase + direction * LOUDNESS_STEP_PERCENT)
            .coerceIn(LOUDNESS_PERCENT_RANGE)
            .toString()
    }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepperIconButton(
                        icon = Icons.Filled.Remove,
                        description = stringResource(
                            R.string.cd_step_decrease_loudness,
                            LOUDNESS_STEP_PERCENT,
                        ),
                        enabled = stepBase > LOUDNESS_PERCENT_RANGE.first,
                        onClick = { step(-1) },
                    )
                    Spacer(Modifier.width(12.dp))
                    CompactNumberField(
                        value = input,
                        onValueChange = { input = it.filter(Char::isDigit).take(LOUDNESS_INPUT_MAX_DIGITS) },
                        suffixText = stringResource(R.string.unit_percent),
                        isError = !isValid,
                        onClickLabel = stringResource(R.string.cd_loudness_input),
                        contentAlpha = numberAlpha.value,
                        modifier = Modifier.width(160.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    StepperIconButton(
                        icon = Icons.Filled.Add,
                        description = stringResource(
                            R.string.cd_step_increase_loudness,
                            LOUDNESS_STEP_PERCENT,
                        ),
                        enabled = stepBase < LOUDNESS_PERCENT_RANGE.last,
                        onClick = { step(1) },
                    )
                }
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
