package com.github.gbandszxc.obt.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.gbandszxc.obt.R

/**
 * 行尾信息图标 + 说明弹窗：多行说明性段落的统一收纳出口。
 *
 * 规则约定：设置行/卡片标题行尾放 24dp 的 InfoOutlined 图标（onSurfaceVariant），
 * 点击弹出 [AlertDialog]（标题 = 对应设置项名，正文 = 说明全文，确认钮「知道了」）；
 * 单行功能性提示与校验错误不走本组件，保持内联。
 * 按钮视觉 32dp、触达面积经 M3 最小交互目标保证 ≥48dp。
 */
@Composable
fun InfoAction(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showDialog = true },
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.cd_info_action, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(text = title) },
            text = { Text(text = description) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(text = stringResource(R.string.info_got_it))
                }
            },
        )
    }
}

