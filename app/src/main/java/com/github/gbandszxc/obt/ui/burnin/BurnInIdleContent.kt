package com.github.gbandszxc.obt.ui.burnin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.playback.BurnInUiState
import com.github.gbandszxc.obt.playback.BurnMode
import com.github.gbandszxc.obt.playback.FreeSoundSelection
import com.github.gbandszxc.obt.playback.PlanCard
import com.github.gbandszxc.obt.playback.formatBurnDuration
import com.github.gbandszxc.obt.ui.HumanDurationPatterns
import com.github.gbandszxc.obt.ui.InfoAction
import com.github.gbandszxc.obt.ui.formatDurationHuman
import com.github.gbandszxc.obt.ui.soundDisplayName

/**
 * 颜色动效规格：ease-out 补间，系统动画关闭（缩放为 0）时直接跳变。
 * 与 [rememberAnimationsEnabled]/[easeOutSpec] 同一可访问口径，作用于描边/强调色切换。
 */
@Composable
private fun easeOutColorSpec(): FiniteAnimationSpec<Color> =
    if (rememberAnimationsEnabled()) {
        tween(durationMillis = 650, easing = EaseOutCubic)
    } else {
        snap()
    }

/**
 * 煲机页未开始态：顶部克制小结（累计煲机）+ 双路线切换（方案煲机 / 自由煲机）+
 * 各路线的配置与开始入口。全部状态由 [BurnInUiState] 单向驱动，事件经回调上抛。
 *
 * ```
 * ┌────────────────────────────┐
 * │         累计煲机             │
 * │         5 小时              │
 * │ [● 方案煲机 | 自由煲机]      │ ← SegmentedButton（icon = {} 去对钩）
 * │（方案煲机态）                │
 * │ ▍标准四阶段 · 120 小时 ⓘ[卡] │ ← 选中主色描边，ⓘ 点开看各阶段音效明细
 * │   上次进度 03:18:22（有则）   │
 * │   [ 继续 ]  [ 全新开始 ]    │
 * │ ▍自定义四阶段 ⓘ        [卡] │ ← ⓘ 点开看四阶段比例说明
 * │   总时长                    │
 * │   (−)  [ 48 小时 ]  (+)    │ ← 40dp 圆形步进 + 160×48 输入框
 * │         [ ▶ 开始煲机 ]      │
 * │ ▍阶段编排 ⓘ            [卡] │ ← 四行拖拽排序 + 响度徽标 + 稳定阶段音乐
 * │（自由煲机态）                │
 * │ 煲机音效 [ 白噪音      ▼ ]   │ ← 分组下拉：内置音效/本地音乐/导入
 * │ 煲机时长 [2h|8h|16h|…]      │
 * │ 自定义小时                  │
 * │ [ 36 小时 ]                │ ← 1-999 校验（错误行内提示）
 * │       [ ▶ 开始煲机 ]        │
 * └────────────────────────────┘
 * ```
 *
 * 「建议插电并保持耳机连接」的多行提示已收进煲机页顶栏行尾 info 图标（见 [com.github.gbandszxc.obt.ui.BurnInApp]）。
 */
@Composable
internal fun IdleContent(
    uiState: BurnInUiState,
    totalCompletedSeconds: Long,
    onModeChange: (BurnMode) -> Unit,
    onPlanCardChange: (PlanCard) -> Unit,
    onPlanCustomHoursChange: (String) -> Unit,
    onStepPlanCustomHours: (Int) -> Unit,
    onStartClassic: (Long?) -> Unit,
    onStartCustom: (Int) -> Unit,
    onStageOrderChange: (List<Int>) -> Unit,
    onStageGainChange: (Int, Double?) -> Unit,
    onSteadyMusicEnabledChange: (Boolean) -> Unit,
    onToggleSteadyTrack: (Long) -> Unit,
    onImportSteadyTrack: (Uri) -> Unit,
    onFreeSoundChange: (FreeSoundSelection) -> Unit,
    onFreePresetHoursChange: (Int) -> Unit,
    onFreeCustomHoursChange: (String) -> Unit,
    onStartFree: () -> Unit,
    onImportTrack: (Uri) -> Unit,
    onDeleteTrack: (LocalTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.label_total_burned),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatDurationHuman(
                    totalCompletedSeconds,
                    HumanDurationPatterns.fromResources(LocalContext.current),
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(20.dp))
        ModeSwitchRow(mode = uiState.mode, onModeChange = onModeChange)
        Spacer(Modifier.height(24.dp))

        when (uiState.mode) {
            BurnMode.PLAN -> PlanModeContent(
                uiState = uiState,
                onPlanCardChange = onPlanCardChange,
                onPlanCustomHoursChange = onPlanCustomHoursChange,
                onStepPlanCustomHours = onStepPlanCustomHours,
                onStartClassic = onStartClassic,
                onStartCustom = onStartCustom,
                onStageOrderChange = onStageOrderChange,
                onStageGainChange = onStageGainChange,
                onSteadyMusicEnabledChange = onSteadyMusicEnabledChange,
                onToggleSteadyTrack = onToggleSteadyTrack,
                onImportSteadyTrack = onImportSteadyTrack,
                onDeleteTrack = onDeleteTrack,
            )
            BurnMode.FREE -> FreeModeContent(
                uiState = uiState,
                onFreeSoundChange = onFreeSoundChange,
                onFreePresetHoursChange = onFreePresetHoursChange,
                onFreeCustomHoursChange = onFreeCustomHoursChange,
                onStartFree = onStartFree,
                onImportTrack = onImportTrack,
                onDeleteTrack = onDeleteTrack,
            )
        }

        // 「建议插电并保持耳机连接，煲机会在后台继续」已收进煲机页顶栏行尾 info 图标，
        // 此处只保留收尾留白
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------
// 模式切换
// ------------------------------------------------------------------

/** 双路线切换：SegmentedButton 选中态仅 tonal 底与描边强调，显式空 icon 去掉默认对钩。 */
@Composable
private fun ModeSwitchRow(mode: BurnMode, onModeChange: (BurnMode) -> Unit) {
    val modes = BurnMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, item ->
            SegmentedButton(
                selected = mode == item,
                onClick = { onModeChange(item) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (item) {
                            BurnMode.PLAN -> R.string.mode_plan
                            BurnMode.FREE -> R.string.mode_free
                        },
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// 方案煲机
// ------------------------------------------------------------------

/**
 * 方案煲机配置区：标准四阶段卡（含续播入口）+ 自定义四阶段卡（总时长步进输入）
 * + 阶段编排配置区（顺序/响度/稳定阶段音乐，见 [StageArrangementSection]）。
 * 卡片展示标题走 UI 层的 [planCardTitle]，不再读取播放层枚举自带的旧文案。
 */
@Composable
private fun PlanModeContent(
    uiState: BurnInUiState,
    onPlanCardChange: (PlanCard) -> Unit,
    onPlanCustomHoursChange: (String) -> Unit,
    onStepPlanCustomHours: (Int) -> Unit,
    onStartClassic: (Long?) -> Unit,
    onStartCustom: (Int) -> Unit,
    onStageOrderChange: (List<Int>) -> Unit,
    onStageGainChange: (Int, Double?) -> Unit,
    onSteadyMusicEnabledChange: (Boolean) -> Unit,
    onToggleSteadyTrack: (Long) -> Unit,
    onImportSteadyTrack: (Uri) -> Unit,
    onDeleteTrack: (LocalTrack) -> Unit,
) {
    val classicSelected = uiState.planCard == PlanCard.CLASSIC
    val crossDayTemplate = stringResource(R.string.duration_cross_day_fmt)

    PlanCard(
        selected = classicSelected,
        onClick = { onPlanCardChange(PlanCard.CLASSIC) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        CardHeader(
            title = planCardTitle(PlanCard.CLASSIC),
            subtitle = stringResource(R.string.card_classic_subtitle),
            accent = classicSelected,
            infoTitle = planCardTitle(PlanCard.CLASSIC),
            infoBody = stringResource(R.string.info_classic_body),
        )
        Spacer(Modifier.height(14.dp))
        // 续播入口只对选中卡展示：resumable 查询跟随当前选中方案（planId）
        val resumable = uiState.resumableSession.takeIf { classicSelected }
        if (resumable != null) {
            Text(
                text = stringResource(
                    R.string.last_progress_fmt,
                    formatBurnDuration(resumable.completedSeconds, crossDayTemplate),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onStartClassic(resumable.completedSeconds) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.btn_resume), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { onStartClassic(null) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.btn_start_fresh), style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            CardStartButton(onClick = { onStartClassic(null) })
        }
    }

    Spacer(Modifier.height(12.dp))

    val customSelected = uiState.planCard == PlanCard.CUSTOM
    val customHours = uiState.planCustomHours
    PlanCard(
        selected = customSelected,
        onClick = { onPlanCardChange(PlanCard.CUSTOM) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 比例说明收进标题行尾 info 图标，点击可查看四阶段详细说明
        CardHeader(
            title = planCardTitle(PlanCard.CUSTOM),
            subtitle = null,
            accent = customSelected,
            infoTitle = planCardTitle(PlanCard.CUSTOM),
            infoBody = stringResource(R.string.info_custom_body),
        )
        Spacer(Modifier.height(14.dp))
        // 步进区与校验错误行包在同一容器：错误行出现/消失时高度做一次克制的尺寸动效
        Column(Modifier.animateContentSize()) {
            HoursStepperRow(
                input = uiState.planCustomHoursInput,
                isError = customHours == null,
                onInputChange = onPlanCustomHoursChange,
                onStep = onStepPlanCustomHours,
                modifier = Modifier.fillMaxWidth(),
            )
            if (customHours == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.error_plan_hours_range,
                        BurnInUiState.PLAN_CUSTOM_HOURS_RANGE.first,
                        BurnInUiState.PLAN_CUSTOM_HOURS_RANGE.last,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        CardStartButton(
            enabled = customHours != null,
            onClick = { customHours?.let(onStartCustom) },
        )
    }

    Spacer(Modifier.height(12.dp))

    // 阶段编排配置区：顺序/响度/稳定阶段音乐，作用于经典与自定义两张卡
    StageArrangementSection(
        uiState = uiState,
        onStageOrderChange = onStageOrderChange,
        onStageGainChange = onStageGainChange,
        onSteadyMusicEnabledChange = onSteadyMusicEnabledChange,
        onToggleSteadyTrack = onToggleSteadyTrack,
        onImportSteadyTrack = onImportSteadyTrack,
        onDeleteTrack = onDeleteTrack,
    )
}

/** 两张方案卡的展示标题（UI 层按资源解析，与 [BurnPlans.CLASSIC] 的方案口径一致）。 */
@Composable
private fun planCardTitle(card: PlanCard): String = stringResource(
    when (card) {
        PlanCard.CLASSIC -> R.string.card_classic_title
        PlanCard.CUSTOM -> R.string.card_custom_title
    },
)

/** 标准四阶段卡的 info 弹窗正文见 strings 的 info_classic_body（与 [BurnPlans.CLASSIC_PHASES] 一致）。 */
/** 自定义四阶段卡的 info 弹窗正文见 strings 的 info_custom_body（与 [BurnPlans.custom] 等比缩放一致）。 */

/**
 * 方案卡：整卡可点选中。选中态主色描边（2dp）+ tonal 底色强调，不使用对钩；
 * 卡内操作区由 [content] 自由组合（开始按钮 / 续播入口）。
 */
@Composable
private fun PlanCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = easeOutColorSpec(),
        label = "planCardBorder",
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                },
                shape,
            )
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = shape)
            .clickable(onClickLabel = stringResource(R.string.cd_select_plan)) { onClick() }
            .padding(16.dp),
        content = content,
    )
}

/** 卡片标题区：主色竖条 + 标题 +（可选）副说明 +（可选）行尾信息图标，选中时竖条与描边同强调。 */
@Composable
private fun CardHeader(
    title: String,
    subtitle: String?,
    accent: Boolean,
    infoTitle: String? = null,
    infoBody: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(width = 3.dp, height = 20.dp)
                .background(
                    color = if (accent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (infoTitle != null && infoBody != null) {
            InfoAction(title = infoTitle, description = infoBody)
        }
    }
}

/** 卡内开始按钮：全宽 44dp，与分段按钮行视觉高度对齐。 */
@Composable
private fun CardStartButton(enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.btn_start_burn), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 总时长步进区：标签行（与控件留 12dp）+ 居中步进控件——40dp 紧凑圆形 −/+ 按钮夹
 * 160×48dp 居中数字输入框，按钮与输入框间距 12dp。
 * 触达边界时对应按钮禁用（视觉降级）；按 −/+ 引起的数值变化做一次约 160ms 的淡入反馈。
 */
@Composable
private fun HoursStepperRow(
    input: String,
    isError: Boolean,
    onInputChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = BurnInUiState.PLAN_CUSTOM_HOURS_RANGE
    val value = input.trim().toIntOrNull()
    // 步进计数：仅 −/+ 触发淡入（手动输入不打断打字节奏）
    var stepTick by remember { mutableStateOf(0) }
    val numberAlpha = remember { Animatable(1f) }
    LaunchedEffect(stepTick) {
        if (stepTick > 0) {
            numberAlpha.snapTo(0.4f)
            numberAlpha.animateTo(1f, tween(durationMillis = 160))
        }
    }

    Column(modifier = modifier) {
        FieldLabel(stringResource(R.string.label_total_duration))
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperIconButton(
                icon = Icons.Filled.Remove,
                description = stringResource(R.string.cd_step_decrease_hours, BurnInUiState.PLAN_CUSTOM_HOURS_STEP),
                enabled = value == null || value > range.first,
                onClick = {
                    stepTick += 1
                    onStep(-1)
                },
            )
            Spacer(Modifier.width(12.dp))
            CompactNumberField(
                value = input,
                onValueChange = onInputChange,
                isError = isError,
                suffixText = stringResource(R.string.unit_hours),
                contentAlpha = numberAlpha.value,
                modifier = Modifier.width(160.dp),
            )
            Spacer(Modifier.width(12.dp))
            StepperIconButton(
                icon = Icons.Filled.Add,
                description = stringResource(R.string.cd_step_increase_hours, BurnInUiState.PLAN_CUSTOM_HOURS_STEP),
                enabled = value == null || value < range.last,
                onClick = {
                    stepTick += 1
                    onStep(1)
                },
            )
        }
    }
}

/** 圆形步进小按钮（40dp），与输入框同高节奏；到边界禁用时由 M3 自动做置灰降级。 */
@Composable
private fun StepperIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ------------------------------------------------------------------
// 自由煲机
// ------------------------------------------------------------------

/** 自由煲机配置区：音效下拉（内置/本地音乐/导入）+ 时长预设与自定义 + 开始按钮。 */
@Composable
private fun FreeModeContent(
    uiState: BurnInUiState,
    onFreeSoundChange: (FreeSoundSelection) -> Unit,
    onFreePresetHoursChange: (Int) -> Unit,
    onFreeCustomHoursChange: (String) -> Unit,
    onStartFree: () -> Unit,
    onImportTrack: (Uri) -> Unit,
    onDeleteTrack: (LocalTrack) -> Unit,
) {
    // 删除确认与 SAF launcher 都留在 UI 层：前者是纯交互状态，后者依赖 Activity Result API
    var pendingDeleteTrack by remember { mutableStateOf<LocalTrack?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportTrack(uri)
    }

    Column(Modifier.fillMaxWidth()) {
        FieldLabel(stringResource(R.string.label_sound))
        Spacer(Modifier.height(8.dp))
        SoundSourceDropdown(
            selected = uiState.freeSound,
            tracks = uiState.tracks,
            importing = uiState.importing,
            onSelect = onFreeSoundChange,
            onImportClick = { importLauncher.launch(arrayOf("audio/*")) },
            onDeleteRequest = { pendingDeleteTrack = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        FieldLabel(stringResource(R.string.label_free_duration))
        Spacer(Modifier.height(8.dp))
        PresetHoursRow(
            uiState = uiState,
            onSelect = onFreePresetHoursChange,
        )
        Spacer(Modifier.height(16.dp))
        // 与方案煲机的总时长步进同一形态：标签在上，160×48 输入框在下（留空则沿用上方预设）
        Column(Modifier.animateContentSize()) {
            FieldLabel(stringResource(R.string.label_custom_hours))
            Spacer(Modifier.height(8.dp))
            CompactNumberField(
                value = uiState.freeCustomHoursInput,
                onValueChange = onFreeCustomHoursChange,
                suffixText = stringResource(R.string.unit_hours),
                hint = stringResource(R.string.hint_optional),
                isError = uiState.freeCustomInputError,
                modifier = Modifier.width(160.dp),
            )
            if (uiState.freeCustomInputError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.error_free_hours_range,
                        BurnInUiState.FREE_CUSTOM_HOURS_RANGE.first,
                        BurnInUiState.FREE_CUSTOM_HOURS_RANGE.last,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStartFree,
            enabled = uiState.canStartFree,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_start_burn), style = MaterialTheme.typography.titleMedium)
        }
    }

    pendingDeleteTrack?.let { track ->
        // 确认弹窗与稳定阶段歌单共用同一形态（见 StageArrangementSection.kt TrackRemoveConfirmDialog）：
        // 只删应用私有目录（filesDir/burn_music/）下的副本与导入记录，源文件不受影响
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

/** 时长预设分段行（QUICK_HOURS）：无对钩；填了自定义输入时预设不显示选中（互斥）。 */
@Composable
private fun PresetHoursRow(uiState: BurnInUiState, onSelect: (Int) -> Unit) {
    val presets = BurnPlans.QUICK_HOURS
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        presets.forEachIndexed { index, hours ->
            SegmentedButton(
                selected = uiState.freeCustomHoursInput.isBlank() && uiState.freePresetHours == hours,
                onClick = { onSelect(hours) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                icon = {},
            ) {
                Text(
                    "${hours}h",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// 通用小组件
// ------------------------------------------------------------------

/** 字段小标签（煲机音效 / 煲机时长），与既有页面的 labelMedium 口径一致。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/**
 * 紧凑数字输入框：48dp 细描边圆角行，数字与行尾单位小字（[suffixText]）整体居中；
 * 数字键盘、单行；空值时显示 [hint] 占位；点整行即可聚焦输入（无障碍动作名 [onClickLabel]）。
 * [contentAlpha] 供步进反馈做淡入；数字过滤与长度截断由调用侧收敛，这里只做展示与上抛。
 * [suffixText] 为行尾单位词（如「小时」），由调用点按当前语言传入；internal 供响度编辑对话框复用。
 */
@Composable
internal fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffixText: String,
    hint: String = "",
    isError: Boolean = false,
    contentAlpha: Float = 1f,
    onClickLabel: String = stringResource(R.string.cd_input_hours),
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor by animateColorAsState(
        targetValue = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.outline
        },
        animationSpec = easeOutColorSpec(),
        label = "numberFieldBorder",
    )
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .graphicsLayer { alpha = contentAlpha }
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = onClickLabel,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 数字区限宽（BasicTextField 在松约束下会撑满）：保证行尾「小时」后缀恒为单行、
        // 且「数字 + 后缀」作为整体在框内居中
        Box(Modifier.widthIn(max = 88.dp), contentAlignment = Alignment.Center) {
            if (value.isEmpty() && hint.isNotEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = inputTextStyle().copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = suffixText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 输入文字样式：与标签/正文同级，颜色固定 onSurface。 */
@Composable
private fun inputTextStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    color = MaterialTheme.colorScheme.onSurface,
)
