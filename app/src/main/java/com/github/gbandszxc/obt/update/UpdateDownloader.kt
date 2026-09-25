package com.github.gbandszxc.obt.update

import com.github.gbandszxc.obt.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 更新包下载器：流式写入 `<cacheRootDir>/updates/<assetName>`，边下边回调进度。
 */
class UpdateDownloader(private val cacheRootDir: File) {

    /**
     * 下载 [info] 指向的 APK。每 ≥500ms 回调一次进度，结束后再回调一次全局平均速度。
     *
     * 失败（网络异常、响应码非 2xx、写入失败）或被取消时都会删除半成品文件，
     * 避免残缺 APK 被随后交给安装器解析。
     *
     * @return 落盘的 APK 文件
     * @throws IOException 网络异常、响应码非 2xx 或写入失败
     */
    suspend fun download(info: UpdateInfo, onProgress: (DownloadProgress) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(cacheRootDir, "updates").apply { mkdirs() }
            val apk = File(dir, info.assetName)
            try {
                downloadTo(info.downloadUrl, info.sizeBytes, apk, onProgress)
            } catch (e: Throwable) {
                // 任何中断（含协程取消）都清理半成品：宁可下次整体重下，也不留下损坏的安装包
                apk.delete()
                throw e
            }
            apk
        }

    /** 在调用方已就绪的 IO 上下文中执行实际网络读取与写盘。 */
    private fun downloadTo(
        downloadUrl: String,
        fallbackSizeBytes: Long,
        apk: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val connection = openConnection(downloadUrl)
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                // 只带技术信息：用户可见文案由调用方拼字符串资源，避免异常 message 串进本地化提示
                throw IOException("HTTP $statusCode")
            }
            // contentLengthLong 更可信；服务端未给出长度时退回检查阶段解析到的大小（可能为 0）
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: fallbackSizeBytes
            val startedAt = System.currentTimeMillis()
            var lastEmitAt = startedAt
            var lastEmitBytes = 0L
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                            val elapsedMs = (now - lastEmitAt).coerceAtLeast(1L)
                            onProgress(
                                DownloadProgress(
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes,
                                    speedBytesPerSecond = (downloaded - lastEmitBytes) * 1000L / elapsedMs,
                                )
                            )
                            lastEmitAt = now
                            lastEmitBytes = downloaded
                        }
                    }
                    val totalElapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                    onProgress(
                        DownloadProgress(
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                            speedBytesPerSecond = downloaded * 1000L / totalElapsedMs,
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Burn-in-Tool/${BuildConfig.VERSION_NAME}")
        }

    private companion object {
        /** 进度回调节流间隔：更密只会徒增 UI 重组，更疏则观感卡顿。 */
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
