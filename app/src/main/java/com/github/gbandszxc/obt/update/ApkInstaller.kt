package com.github.gbandszxc.obt.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 把下载好的 APK 交给系统安装器。 */
object ApkInstaller {

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /**
     * 发起安装（FileProvider + ACTION_VIEW + application/vnd.android.package-archive）。
     *
     * @return true 表示已发出安装 Intent；false 表示本应用尚未被允许「安装未知应用」，
     *         此时已跳转系统设置页引导用户开启，调用方应提示用户开启后重试。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // 用户未授权：跳转授权页后返回 false，由调用方提示「开启后重试」。
            // 跳转本身可能因 ROM 缺失该设置页而失败，失败时静默——返回值语义仍然成立。
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            return false
        }
        // authority 与 Manifest 的 ${applicationId}.fileprovider 一致：本模块无 applicationIdSuffix
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
