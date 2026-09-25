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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.gbandszxc.obt.BuildConfig
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.data.AppLanguage
import com.github.gbandszxc.obt.data.ThemeMode
import com.github.gbandszxc.obt.ui.InfoAction
import com.github.gbandszxc.obt.ui.theme.ThemePalettes

/** 项目 GitHub 仓库地址（开源后替换为正式仓库地址）。 */
const val GITHUB_URL = "https://github.com/gbandszxc/open-burnin-tool"

/**
 * 设置 Tab：通用（语言）+ 外观（主题模式/动态取色/主题配色）+ 播放（屏幕常亮/不息屏）+ 关于。
 * 状态由 [SettingsUiState] 单向驱动，全部变更经挂起回调写回 [com.github.gbandszxc.obt.data.SettingsRepository]；
 * 语言切换由调用方（BurnInApp）负责更新进程内当前语言并重建界面。
 * 多行说明性段落一律收进行尾 ⓘ 图标弹窗（[InfoAction]），页面内只留单行功能性提示。
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 通用                          │
 * │  语言                         │
 * │  [跟随系统 | 中文 | English]   │ ← SegmentedButton（切换即生效并重建）
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
 * │  版本            1.4.0 [↗]   │ ← [↗] 跳转 GitHub
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
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_group_general))

        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        LanguageRow(selected = state.language, onSelect = onLanguageChange)

        SectionHeader(stringResource(R.string.settings_group_appearance))

        Text(
            text = stringResource(R.string.settings_theme_mode),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        val themeModes = listOf(
            ThemeMode.SYSTEM to stringResource(R.string.theme_system),
            ThemeMode.LIGHT to stringResource(R.string.theme_light),
            ThemeMode.DARK to stringResource(R.string.theme_dark),
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
                    Text(
                        label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            checked = state.dynamicColor,
            onCheckedChange = onDynamicColorChange,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // 低版本置灰原因属单行功能性提示，保留内联（超出部分不展开）
            Caption(stringResource(R.string.caption_dynamic_color_unavailable))
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_palette),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        PaletteRow(
            selectedId = state.paletteId,
            onSelect = onPaletteChange,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(stringResource(R.string.settings_group_playback))
        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            checked = state.keepScreenOn,
            onCheckedChange = onKeepScreenOnChange,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = stringResource(R.string.settings_dim_keep_alive),
            checked = state.dimKeepAlive,
            onCheckedChange = onDimKeepAliveChange,
            info = stringResource(R.string.info_dim_body),
        )

        SectionHeader(stringResource(R.string.settings_group_about))
        AboutRow(modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 语言切换行：跟随系统（自动检测）/ 中文 / English 三选一。
 * 中文与 English 两个选项的文案固定用各自语言自称（语言列表的通行惯例），
 * 仅「跟随系统」随当前应用语言翻译。
 */
@Composable
private fun LanguageRow(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    val languages = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.language_system),
        AppLanguage.CHINESE to "中文",
        AppLanguage.ENGLISH to "English",
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        languages.forEachIndexed { index, (language, label) ->
            SegmentedButton(
                selected = selected == language,
                onClick = { onSelect(language) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = languages.size),
                icon = {},
            ) {
                Text(
                    label,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 分组标题：通用 / 外观 / 播放 / 关于。 */
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
            val selectLabel = stringResource(R.string.palette_select_label, stringResource(palette.labelRes))
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
                    .clickable(onClickLabel = selectLabel) {
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
            text = stringResource(R.string.settings_version),
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
                contentDescription = stringResource(R.string.cd_open_github),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
