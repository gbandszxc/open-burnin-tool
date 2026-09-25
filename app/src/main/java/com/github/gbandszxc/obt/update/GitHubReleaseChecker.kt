package com.github.gbandszxc.obt.update

import android.os.Build
import com.github.gbandszxc.obt.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GitHub Release 更新检查器。
 *
 * 走公开 HTML 页面而非 GitHub API：`/releases/latest` 跟随重定向拿 tag，
 * `/releases/expanded_assets/<tag>` 拿资产列表。公开页面无需 token，也就没有匿名 API 限流问题。
 *
 * 网络与解析失败统一抛异常（含非 2xx 的 [IOException]），由调用方转成用户提示；
 * 「仓库尚无 Release」这类正常结局收敛为 [UpdateCheckResult.UpToDate]，不视为错误。
 */
class GitHubReleaseChecker(
    private val owner: String = OWNER,
    private val repo: String = REPO,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) {

    /** 当前设备用于匹配的 ABI（弹窗文案要显示它）。 */
    val currentAbi: String
        get() = selectAbi(supportedAbis)

    /**
     * 抓取 GitHub Release 判定是否有更新。网络/解析失败抛异常，由调用方转成用户提示。
     */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val latestPage = readPage("$GITHUB_BASE_URL/$owner/$repo/releases/latest")
        // 重定向成功后最终 URL 末段即 tag；仍停在 /latest（未重定向）时回落到 HTML 里解析
        val tag = latestPage.finalUrl.substringAfterLast('/').takeIf {
            it.isNotBlank() && !it.equals("latest", ignoreCase = true)
        } ?: parseLatestReleaseTag(latestPage.content, owner, repo)
        if (tag.isBlank()) return@withContext UpdateCheckResult.UpToDate

        val assetsHtml = readText("$GITHUB_BASE_URL/$owner/$repo/releases/expanded_assets/$tag")
        if (compareVersions(normalizeVersion(tag), currentVersionName) <= 0) {
            return@withContext UpdateCheckResult.UpToDate
        }
        val info = findMatchingAsset(currentAbi, tag, assetsHtml)
        if (info != null) UpdateCheckResult.Available(info)
        else UpdateCheckResult.Unsupported(normalizeVersion(tag))
    }

    private fun readPage(url: String): PageResponse {
        val connection = openConnection(url)
        try {
            ensureSuccess(connection)
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            return PageResponse(finalUrl = connection.url.toString(), content = content)
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(url: String): String {
        val connection = openConnection(url)
        try {
            ensureSuccess(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 非 2xx（含 404/5xx）统一抛出带状态码的 [IOException]，便于 UI 显示「检查更新失败：HTTP 404」。 */
    private fun ensureSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("User-Agent", "Burn-in-Tool/$currentVersionName")
        }

    /** 一次页面读取的最终 URL 与正文（重定向后的 URL 用于提取 tag）。 */
    private data class PageResponse(
        val finalUrl: String,
        val content: String,
    )

    companion object {
        const val OWNER = "gbandszxc"
        const val REPO = "open-burnin-tool"
    }
}
