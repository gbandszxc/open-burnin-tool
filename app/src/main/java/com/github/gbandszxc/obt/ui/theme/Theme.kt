package com.github.gbandszxc.obt.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.github.gbandszxc.obt.data.DEFAULT_PALETTE_ID
import com.github.gbandszxc.obt.data.ThemeMode

/**
 * 按 [ThemeMode] 解析当前实际生效的深浅色：
 * - [ThemeMode.SYSTEM] 跟随系统（isSystemInDarkTheme）；
 * - [ThemeMode.LIGHT] / [ThemeMode.DARK] 应用内强制，忽略系统设置。
 * 供主题与宿主（如系统栏样式）共用同一判定，保证两者永远一致。
 */
@Composable
fun resolveDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * 应用主题：
 * - [themeMode]：跟随系统 / 强制浅色 / 强制深色，默认跟随系统（与改造前行为一致）；
 * - [paletteId]：预置调色盘 id（见 [ThemePalettes]），未知 id 回退默认「青瓷绿」；
 * - [dynamicColor]：Android 12+ 跟随壁纸动态取色，开启时优先于预置调色盘；
 *   低版本无动态取色能力，该开关不生效、始终走预置调色盘。
 */
@Composable
fun BurnInTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    paletteId: String = DEFAULT_PALETTE_ID,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode)
    val palette = ThemePalettes.firstOrNull { it.id == paletteId } ?: ThemePalettes.first()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> palette.dark
        else -> palette.light
    }
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
