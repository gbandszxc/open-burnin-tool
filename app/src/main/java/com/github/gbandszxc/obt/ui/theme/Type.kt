package com.github.gbandszxc.obt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * 应用排版：沿用默认系统字体家族（Roboto 多字重），只做两类收敛：
 * - 标题类字重统一到 SemiBold，建立清晰的层级对比；
 * - 展示级（display*）数字启用 `tnum` 等宽数字特性，煲机计时逐秒变化时不跳动。
 */
val AppTypography: Typography = run {
    val base = Typography()
    base.copy(
        displayLarge = base.displayLarge.copy(fontFeatureSettings = "tnum"),
        displayMedium = base.displayMedium.copy(fontFeatureSettings = "tnum"),
        displaySmall = base.displaySmall.copy(fontFeatureSettings = "tnum"),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}
