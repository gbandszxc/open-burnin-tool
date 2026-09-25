package com.github.gbandszxc.obt.update

/** GitHub 站点根，路径拼接与「相对路径补全为绝对 URL」共用。 */
internal const val GITHUB_BASE_URL = "https://github.com"

/** 本仓库按 ABI 分包的档位：设备支持时 arm64-v8a 优先，其次 armeabi-v7a。 */
internal val SHIPPED_ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a")

/** 去掉首部 v/V 并 trim。 */
internal fun normalizeVersion(raw: String): String =
    raw.trim().removePrefix("v").removePrefix("V")

/**
 * 逐段整数比较版本号：left > right 返回正数，相等返回 0。
 *
 * 按 `.`、`-`、`_` 切段，每段只取前导数字（非数字段丢弃），再逐位比较、缺位补 0。
 * 之所以不直接比字符串：`1.10.0` 与 `1.9.9` 的字典序会把前者判小，与实际发布顺序相反。
 */
internal fun compareVersions(left: String, right: String): Int {
    val leftParts = versionParts(left)
    val rightParts = versionParts(right)
    val size = maxOf(leftParts.size, rightParts.size)
    for (i in 0 until size) {
        val l = leftParts.getOrElse(i) { 0 }
        val r = rightParts.getOrElse(i) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

/** 版本号切段取前导数字；如 `1.10.0-rc1` → [1, 10, 0, 1]。 */
private fun versionParts(version: String): List<Int> =
    normalizeVersion(version)
        .split('.', '-', '_')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

/**
 * 从 /releases/latest 的最终页 HTML 兜底解析 release tag（重定向未生效时用）。
 *
 * 只在锚点的 href 里找 `/<owner>/<repo>/releases/tag/<tag>`；owner/repo 由参数注入并作正则转义，
 * 以免仓库改名后残留的硬编码路径失配。
 */
internal fun parseLatestReleaseTag(html: String, owner: String, repo: String): String {
    val pattern = Regex(
        """/${Regex.escape(owner)}/${Regex.escape(repo)}/releases/tag/([^"?#<]+)"""
    )
    return pattern.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace("&quot;", "")
        .orEmpty()
}

/**
 * 设备 ABI 选择：优先 arm64-v8a，其次 armeabi-v7a。
 *
 * 返回空串表示设备架构不在本项目分包档位（如纯 x86 设备），调用方应视为无适配包。
 * 这里不回退 arm64-v8a：那会让 x86 设备命中 arm64 资产、弹出在它上面根本装不上的包。
 */
internal fun selectAbi(supportedAbis: List<String>): String =
    SHIPPED_ABIS.firstOrNull { it in supportedAbis } ?: ""

/**
 * 从 expanded_assets 页面 HTML 提取全部 APK 下载链接（按出现顺序，&amp; 还原为 &）。
 *
 * 仅取 `.apk` 结尾（后缀忽略大小写），返回 GitHub 相对路径或原样的绝对 URL。
 */
internal fun extractApkDownloadPaths(expandedAssetsHtml: String): List<String> =
    APK_HREF_REGEX.findAll(expandedAssetsHtml)
        .map { it.groupValues[1].replace("&amp;", "&") }
        .toList()

/** 匹配 `href="...releases/download/....apk"`；`.apk` 用内联开关忽略大小写，路径部分保持精确匹配。 */
private val APK_HREF_REGEX = Regex("""href="([^"]*releases/download/[^"]+(?i:\.apk))"""")

/**
 * 从候选资产中挑出「release 变体 + 当前 ABI + 版本号一致」的安装包；找不到返回 null。
 *
 * 严格排除 `-debug`：同一 Release 若同时挂了调试包，不能被误当成可安装更新。
 * 本函数只做「资产是否可用」的判定，不比较版本新旧（那属于 [GitHubReleaseChecker.check] 的职责）。
 */
internal fun findMatchingAsset(
    currentAbi: String,
    releaseVersionName: String,
    expandedAssetsHtml: String,
): UpdateInfo? {
    // selectAbi 返回空串表示设备架构不在分包档位，直接判为无适配包（不回退 arm64）
    if (currentAbi.isBlank()) return null
    val version = normalizeVersion(releaseVersionName)
    if (version.isBlank()) return null
    val abiToken = "-$currentAbi-"
    val versionToken = "-v$version-"
    return extractApkDownloadPaths(expandedAssetsHtml).firstNotNullOfOrNull { path ->
        val assetName = path.substringAfterLast('/')
        if (!assetName.endsWith(".apk", ignoreCase = true)) return@firstNotNullOfOrNull null
        if (assetName.contains("-debug", ignoreCase = true)) return@firstNotNullOfOrNull null
        if (!assetName.contains("-release", ignoreCase = true)) return@firstNotNullOfOrNull null
        if (!assetName.contains(abiToken, ignoreCase = true)) return@firstNotNullOfOrNull null
        if (!assetName.contains(versionToken, ignoreCase = true)) return@firstNotNullOfOrNull null
        UpdateInfo(
            versionName = version,
            assetName = assetName,
            downloadUrl = if (path.startsWith("http")) path else "$GITHUB_BASE_URL$path",
            sizeBytes = 0L,
        )
    }
}
