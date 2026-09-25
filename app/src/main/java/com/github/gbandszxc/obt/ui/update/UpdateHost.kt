package com.github.gbandszxc.obt.ui.update

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.update.ApkInstaller
import com.github.gbandszxc.obt.update.DownloadProgress
import com.github.gbandszxc.obt.update.UpdateInfo
import java.util.Locale

/**
 * 更新流程的弹窗宿主：只负责按状态渲染弹窗与消费事件，不承载业务逻辑。
 *
 * 五种状态对应五类弹窗——检查中 / 发现新版本 / 稍后三档 / 下载进度 / 检查结果通知；
 * 检查中与下载进度为不可取消（避免半途打断网络操作），其余可取消。
 * 所有用户可见文案走字符串资源，数值格式化在本文件内用私有函数完成。
 */
@Composable
fun UpdateHost(viewModel: UpdateViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UpdateEvent.Message ->
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is UpdateEvent.Install ->
                    // install 返回 false 表示未授予「安装未知应用」，此时它已跳转系统设置页
                    if (!ApkInstaller.install(context, event.apk)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_install_permission_needed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    when (val current = state) {
        UpdateUiState.Idle -> Unit
        UpdateUiState.Checking -> CheckingDialog()
        is UpdateUiState.Available ->
            if (current.showSnoozeOptions) {
                SnoozeDialog(
                    onDismiss = viewModel::dismiss,
                    onSnoozeThisSession = viewModel::snoozeOnce,
                    onSnoozeSevenDays = viewModel::snoozeForSevenDays,
                    onSnoozeNextVersion = viewModel::snoozeUntilNextVersion,
                )
            } else {
                UpdateAvailableDialog(
                    versionName = current.info.versionName,
                    abi = viewModel.currentAbi,
                    assetName = current.info.assetName,
                    onDownload = viewModel::downloadAndInstall,
                    onLater = viewModel::requestSnoozeOptions,
                )
            }
        is UpdateUiState.Downloading -> DownloadingDialog(info = current.info, progress = current.progress)
        is UpdateUiState.Notice -> NoticeDialog(notice = current, onDismiss = viewModel::dismiss)
    }
}

/** 检查中：不可取消，标题 + 说明 + 居中转圈。 */
@Composable
private fun CheckingDialog() {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(text = stringResource(R.string.update_checking_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.update_checking_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
    )
}

/** 发现新版本：展示版本与匹配到的安装包，确认下载安装、取消进入「稍后」三档。 */
@Composable
private fun UpdateAvailableDialog(
    versionName: String,
    abi: String,
    assetName: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        // 点外部/返回视同「稍后」：不直接关闭，而是进入稍后选项，避免误触后无事发生
        onDismissRequest = onLater,
        title = { Text(text = stringResource(R.string.update_found_title, versionName)) },
        text = { Text(text = stringResource(R.string.update_found_message, abi, assetName)) },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(text = stringResource(R.string.update_download_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(text = stringResource(R.string.update_later))
            }
        },
    )
}

/** 稍后三档：说明 + 三个整行可点的选项（本次 / 7 天 / 下个版本）。 */
@Composable
private fun SnoozeDialog(
    onDismiss: () -> Unit,
    onSnoozeThisSession: () -> Unit,
    onSnoozeSevenDays: () -> Unit,
    onSnoozeNextVersion: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.update_snooze_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.update_snooze_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SnoozeOptionRow(
                    label = stringResource(R.string.update_snooze_this_session),
                    onClick = onSnoozeThisSession,
                )
                SnoozeOptionRow(
                    label = stringResource(R.string.update_snooze_7_days),
                    onClick = onSnoozeSevenDays,
                )
                SnoozeOptionRow(
                    label = stringResource(R.string.update_snooze_next_version),
                    onClick = onSnoozeNextVersion,
                )
            }
        },
        confirmButton = {},
    )
}

/** 稍后选项行：整行可点，主色文字，最小高 48dp。 */
@Composable
private fun SnoozeOptionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 下载进度：不可取消，文件名 + 进度条 + 速度/大小一行 + 说明。 */
@Composable
private fun DownloadingDialog(info: UpdateInfo, progress: DownloadProgress) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(text = stringResource(R.string.update_downloading_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = info.assetName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                if (progress.totalBytes > 0L) {
                    // 总量已知 → 确定进度；比例夹到 0..1，避免异常值溢出。
                    // 传空 lambda 覆盖 drawStopIndicator 的默认实现，去掉轨道末端的停止指示点。
                    LinearProgressIndicator(
                        progress = {
                            (progress.downloadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                    )
                } else {
                    // 总量未知 → 不确定态（该重载本身不绘制停止指示点，无需额外参数）
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatSpeed(context, progress.speedBytesPerSecond),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatSize(context, progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.update_download_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}

/** 检查结果通知：标题 + 可选正文 + 确认钮「知道了」，确认与取消都只是关闭弹窗。 */
@Composable
private fun NoticeDialog(notice: UpdateUiState.Notice, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = notice.title) },
        // 正文为空则完全不渲染该区域，避免出现空白的 text 插槽
        text = if (notice.message.isEmpty()) {
            null
        } else {
            { Text(text = notice.message) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.info_got_it))
            }
        },
    )
}

/** 速度文案：未知速度走专用占位，其余按 MB/s 保留一位小数（单位走字符串资源）。 */
private fun formatSpeed(context: Context, speedBytesPerSecond: Long): String =
    if (speedBytesPerSecond <= 0L) {
        context.getString(R.string.update_speed_unknown)
    } else {
        context.getString(R.string.update_speed_value, formatMegabytes(speedBytesPerSecond))
    }

/** 大小文案：总量已知显示「已下载 / 总量」，未知只显示「已下载」。 */
private fun formatSize(context: Context, progress: DownloadProgress): String =
    if (progress.totalBytes > 0L) {
        context.getString(
            R.string.update_size_value,
            formatMegabytes(progress.downloadedBytes),
            formatMegabytes(progress.totalBytes),
        )
    } else {
        context.getString(R.string.update_downloaded_only, formatMegabytes(progress.downloadedBytes))
    }

/** 字节 → MB 字符串（一位小数）。固定用 Locale.US，避免部分地区默认 Locale 输出逗号小数点。 */
private fun formatMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0)
