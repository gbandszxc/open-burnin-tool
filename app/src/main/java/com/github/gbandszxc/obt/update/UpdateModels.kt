package com.github.gbandszxc.obt.update

/** 一次更新检查的结果。 */
sealed interface UpdateCheckResult {
    /** 已是最新版本。 */
    data object UpToDate : UpdateCheckResult
    /** 有新版本且已匹配到适用于当前架构的安装包。 */
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    /** 有新版本，但 Release 中没有适用于当前架构的安装包。 */
    data class Unsupported(val versionName: String) : UpdateCheckResult
}

/** 从 GitHub Release 匹配到的新版本安装包。 */
data class UpdateInfo(
    val versionName: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
)

/** 下载进度快照。 */
data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
)
