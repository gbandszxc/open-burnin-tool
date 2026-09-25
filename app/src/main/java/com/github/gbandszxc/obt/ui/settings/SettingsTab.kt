package com.github.gbandszxc.obt.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.gbandszxc.obt.BuildConfig
import com.github.gbandszxc.obt.data.ThemeMode
import com.github.gbandszxc.obt.ui.InfoAction
import com.github.gbandszxc.obt.ui.theme.ThemePalettes

/** 项目 GitHub 仓库地址（开源后替换为正式仓库地址）。 */
const val GITHUB_URL = "https://github.com/gbandszxc/open-burnin-tool"

/** 「不息屏模式」说明全文：多行说明性段落，收进行尾 info 图标，点击弹窗查看。 */
private const val DIM_KEEP_ALIVE_INFO =
    "煲机播放中屏幕保持常亮，停止操作达到系统息屏时长后自动降至最低亮度，" +
        "避免部分机型息屏后中断后台播放；暂停或结束即恢复正常。"

/**
 * 设置 Tab：外观（主题模式/动态取色/主题配色）+ 播放（屏幕常亮/不息屏）+ 关于。
 * 状态由 [SettingsUiState] 单向驱动，全部变更经挂起回调写回 [com.github.gbandszxc.obt.data.SettingsRepository]。
 * 多行说明性段落一律收进行尾 ⓘ 图标弹窗（[InfoAction]），页面内只留单行功能性提示。
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 外观                          │
 * │  主题模式                      │
 * │  [跟随系统 | 浅色 | 深色]       │ ← SegmentedButton
 * │  动态取色               ◉ 开   │ ← Switch（Android 12+ 有效，低版本置灰+一行短提示）
 * │  主题配色                      │
 * │  ● ● ● ● ● ●                 │ ← 六张色卡，选中加主色描边
 * │ 播放                          │
 * │  屏幕常亮               ◉      │ ← 与煲机页顶栏同一字段
 * │  不息屏模式            ⓘ ○    │ ← 说明收进行尾 ⓘ，点击弹窗
 * │ 关于                          │
 * │  版本            1.2.0 [↗]   │ ← [↗] 跳转 GitHub
 * └──────────────────────────────┘
 * ```
 */
@Composable
fun SettingsTab(
    state: SettingsUiState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onPaletteChange: (String) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDimKeepAliveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        SectionHeader("外观")

        Text(
            text = "主题模式",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        val themeModes = listOf(
            ThemeMode.SYSTEM to "跟随系统",
            ThemeMode.LIGHT to "浅色",
            ThemeMode.DARK to "深色",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            themeModes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = themeModes.size),
                    // 选中态仅靠 tonal 底色与描边强调，显式传空 icon 去掉默认对钩，避免文字被挤向右侧
                    icon = {},
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "动态取色",
            checked = state.dynamicColor,
            onCheckedChange = onDynamicColorChange,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // 低版本置灰原因属单行功能性提示，保留内联（超出部分不展开）
            Caption("跟随壁纸取色需要 Android 12 及以上，当前系统不支持")
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "主题配色",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        PaletteRow(
            selectedId = state.paletteId,
            onSelect = onPaletteChange,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader("播放")
        SwitchRow(
            title = "屏幕常亮",
            checked = state.keepScreenOn,
            onCheckedChange = onKeepScreenOnChange,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "不息屏模式",
            checked = state.dimKeepAlive,
            onCheckedChange = onDimKeepAliveChange,
            info = DIM_KEEP_ALIVE_INFO,
        )

        SectionHeader("关于")
        AboutRow(modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
    }
}

/** 分组标题：外观 / 播放 / 关于。 */
@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
}

/**
 * 开关行：整行可点（toggleable 语义），行尾 [Switch] 仅作状态展示，避免双重响应。
 * [info] 非空时在开关左侧放 ⓘ 图标（[InfoAction]），多行说明收进弹窗而不内联铺开。
 * [enabled] 为 false 时整行置灰（低版本无动态取色能力）。
 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    info: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (info != null) {
            InfoAction(title = title, description = info)
            Spacer(Modifier.width(4.dp))
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 主题配色行：六张圆形色卡（预置调色盘 preview 色），选中外圈主色描边。 */
@Composable
private fun PaletteRow(
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ThemePalettes.forEach { palette ->
            val selected = palette.id == selectedId
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(palette.preview)
                    .clickable(onClickLabel = "选择配色：${palette.label}") {
                        onSelect(palette.id)
                    },
            )
        }
    }
}

/** 单行功能性提示：紧跟上一行控件的补充解释（多行说明应改用 [InfoAction] 收进弹窗）。 */
@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 关于行：仅保留「版本 + 版本号」（取 BuildConfig，跟随 versionName，单一事实来源），
 * 行尾 GitHub 跳转图标按钮（打开 [GITHUB_URL]，无浏览器时静默忽略）。
 */
@Composable
private fun AboutRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "版本",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                }
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = "在浏览器打开 GitHub 仓库",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
