package com.github.gbandszxc.obt.ui.burnin

import android.provider.Settings
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * 系统动画时长缩放（ANIMATOR_DURATION_SCALE）：关闭动画（=0）时所有补间直接跳变。
 * 读取一次即可，运行中改开发者选项的场景可忽略。
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/** ease-out 补间规格：系统动画关闭时返回直接跳变，动效克制且可访问。 */
@Composable
fun easeOutSpec(): FiniteAnimationSpec<Float> =
    if (rememberAnimationsEnabled()) tween(durationMillis = 650, easing = EaseOutCubic) else snap()

/**
 * 煲机进度环：底部整圈轨道 + 顶部圆角进度弧（自 12 点方向顺时针），
 * 中心内容由 [content] 自由组合（大号计时数字）。
 * 进度变化用 ease-out 补间平滑追随；progress ≤ 0 时只画轨道（避免 RoundCap 孤点）。
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    stroke: Dp = 12.dp,
    color: Color,
    trackColor: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    val animated: Float by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = easeOutSpec(),
        label = "burnProgress",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokePx = stroke.toPx()
            val diameter = min(size.width, size.height) - strokePx
            if (diameter <= 0f) return@Canvas
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val arcStyle = Stroke(width = strokePx, cap = StrokeCap.Round)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = arcStyle,
            )
            val sweep = 360f * animated
            if (sweep > 1f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = arcStyle,
                )
            }
        }
        content()
    }
}
