package com.github.gbandszxc.obt.ui.burnin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.domain.model.SoundSource
import com.github.gbandszxc.obt.playback.FreeSoundSelection

/** 菜单内容最大高度（约 6-7 行可见，超出滚动），避免曲目多时菜单溢出屏幕。 */
private const val MENU_MAX_HEIGHT_DP = 380

/**
 * 煲机音效下拉框（自由煲机）：收起态为只读触发行（44dp，与分段按钮行视觉对齐），
 * 展开态菜单按「内置音效 / 本地音乐」分组，菜单底部常驻「导入本地音乐…」入口。
 *
 * 选中强调只用主色文字与描边，不用对钩；每条本地音乐行尾附删除图标，
 * 点击后经 [onDeleteRequest] 上抛（确认对话框由调用方持有，删除动作不做在本组件内）。
 *
 * @param selected 当前选中音效。
 * @param tracks 已导入的本地音轨（按加入时间倒序）。
 * @param importing 导入进行中：导入项显示进度并禁用。
 * @param onSelect 选中某项（内置音源或本地曲目）。
 * @param onImportClick 触发系统文件选择（SAF）导入。
 * @param onDeleteRequest 请求删除某条本地曲目（调用方弹确认后执行）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoundSourceDropdown(
    selected: FreeSoundSelection,
    tracks: List<LocalTrack>,
    importing: Boolean,
    onSelect: (FreeSoundSelection) -> Unit,
    onImportClick: () -> Unit,
    onDeleteRequest: (LocalTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        // menuAnchor 是 ExposedDropdownMenuBoxScope 的扩展，只能在 scope 内调用，
        // 这里在 scope 内构造后传给触发行
        TriggerRow(
            selected = selected,
            expanded = expanded,
            anchorModifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(
                // 根因：verticalScroll 必须放在 heightIn(max) 之后。Modifier 链外层先接收
                // 父级约束，ExposedDropdownMenu 弹窗内容以无限最大高度测量；若滚动修饰符
                // 在前会直接拿到 Infinity 而抛 IllegalStateException（展开即崩）。
                // 先钳高再滚动，滚动容器才有确定的最大高度约束。
                Modifier
                    .heightIn(max = MENU_MAX_HEIGHT_DP.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionHeader("内置音效")
                SoundSource.catalog.forEach { sound ->
                    val isSelected = selected is FreeSoundSelection.Builtin && selected.sound == sound
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = sound.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        onClick = {
                            onSelect(FreeSoundSelection.Builtin(sound))
                            expanded = false
                        },
                    )
                }
                MenuDivider()
                SectionHeader("本地音乐")
                if (tracks.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "暂无本地音乐，可从下方导入",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }
                tracks.forEach { track ->
                    val isSelected = selected is FreeSoundSelection.LocalMusic &&
                        selected.track.id == track.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = track.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onSelect(FreeSoundSelection.LocalMusic(track))
                            expanded = false
                        },
                        trailingIcon = {
                            IconButton(onClick = { onDeleteRequest(track) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "移除「${track.displayName}」",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
                MenuDivider()
                DropdownMenuItem(
                    text = {
                        if (importing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "正在导入…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                text = "＋ 导入本地音乐…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onImportClick()
                    },
                    enabled = !importing,
                )
            }
        }
    }
}

/** 收起态触发行：只读展示当前音效名 + 展开箭头，[anchorModifier]（menuAnchor）驱动展开/收起。 */
@Composable
private fun TriggerRow(
    selected: FreeSoundSelection,
    expanded: Boolean,
    anchorModifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(anchorModifier)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(
                width = 1.dp,
                color = if (expanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = shape,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = selected.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = if (expanded) "收起音效列表" else "展开音效列表",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 菜单分组标题（内置音效 / 本地音乐），非可点。 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 菜单分组之间的细分隔线。 */
@Composable
private fun MenuDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
