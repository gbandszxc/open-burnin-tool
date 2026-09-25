package com.github.gbandszxc.obt.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReleaseParsing.kt 纯逻辑测试：版本号归一化与逐段比较、设备 ABI 选择、
 * `/releases/latest` 页面 tag 兜底解析、`expanded_assets` 资产链接提取，
 * 以及「release 变体 + 当前 ABI + 版本号一致」的资产匹配（[findMatchingAsset]）。
 *
 * 覆盖点：
 * - [normalizeVersion]：去掉首部 v/V、去除首尾空白；
 * - [compareVersions]：逐段数值比较（`1.10.0 > 1.9.9` 是字典序会判反的关键回归点）、缺位补 0、v 前缀等价；
 * - [selectAbi]：arm64-v8a 优先且不受设备 ABI 列表顺序影响，非 ARM 设备与空列表返回空串（无适配包）；
 * - [parseLatestReleaseTag]：href 形式与 `&quot;` 实体引号形式命中、无 tag 返回空串、跨仓库不误配；
 * - [extractApkDownloadPaths]：按文档顺序提取、忽略源码 zip；
 *   注意提取层只抓 `releases/download` 路径下以 `.apk` 结尾的链接，**不排除 `-debug`**，debug 的排除在 [findMatchingAsset]；
 * - [findMatchingAsset]：架构与版本双重命中、版本/架构不符或仅 debug、空页面时返回 null。
 *
 * 全部用例仅用本地 fixture，不依赖网络。
 */
class ReleaseParsingTest {

    // ---- normalizeVersion ----

    @Test
    fun `归一化去掉首部v或V并去除首尾空白`() {
        assertEquals("1.5.0", normalizeVersion("v1.5.0"))
        assertEquals("1.5.0", normalizeVersion("V1.5.0"))
        assertEquals("1.5.0", normalizeVersion(" 1.5.0 "))
        assertEquals("1.5.0", normalizeVersion("1.5.0"))
        // 仅一个前缀被剥离，空串与单字符 v 归一化后为空
        assertEquals("", normalizeVersion("v"))
        assertEquals("", normalizeVersion("   "))
    }

    // ---- compareVersions ----

    @Test
    fun `逐段数值比较而非字典序_1_10_0大于1_9_9`() {
        // 字典序会把 "1.10.0" 判小于 "1.9.9"（'1' < '9'），实际发布顺序相反
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.9.9", "1.10.0") < 0)
        assertEquals(0, compareVersions("1.10.0", "1.10.0"))
        assertTrue(compareVersions("1.2.30", "1.2.4") > 0)
    }

    @Test
    fun `带v前缀与缺位补零的版本等价`() {
        assertEquals(0, compareVersions("1.0.5", "v1.0.5"))
        assertEquals(0, compareVersions("v1.0.5", "V1.0.5"))
        assertEquals(0, compareVersions("1.5", "1.5.0"))
        assertEquals(0, compareVersions("1.5.0", "1.5"))
        assertEquals(0, compareVersions("1.5", "1.5.0.0"))
    }

    @Test
    fun `主次版本比较方向正确`() {
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.4.9", "1.5.0") < 0)
        assertTrue(compareVersions("1.5.0", "1.4.9") > 0)
    }

    @Test
    fun `归一化后比较与直接比较结果一致`() {
        assertEquals(0, compareVersions(normalizeVersion("v1.5.0"), "1.5.0"))
        assertEquals(0, compareVersions(normalizeVersion(" 1.10.0 "), "v1.10.0"))
        assertTrue(compareVersions(normalizeVersion("v1.10.0"), normalizeVersion("V1.9.9")) > 0)
    }

    // ---- selectAbi ----

    @Test
    fun `支持arm64时优先选择arm64且不受列表顺序影响`() {
        assertEquals("arm64-v8a", selectAbi(listOf("armeabi-v7a", "arm64-v8a", "x86_64")))
        assertEquals("arm64-v8a", selectAbi(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm64-v8a", selectAbi(listOf("x86_64", "arm64-v8a")))
        assertEquals("arm64-v8a", selectAbi(SHIPPED_ABIS))
    }

    @Test
    fun `仅支持v7a时选择v7a`() {
        assertEquals("armeabi-v7a", selectAbi(listOf("armeabi-v7a", "x86")))
        assertEquals("armeabi-v7a", selectAbi(listOf("x86", "armeabi-v7a")))
        assertEquals("armeabi-v7a", selectAbi(listOf("armeabi-v7a")))
    }

    @Test
    fun `设备架构均不在分包档位时返回空串`() {
        // x86 模拟器等非 ARM 设备：本项目无对应分包，返回空串表示无适配包
        assertEquals("", selectAbi(listOf("x86_64", "x86")))
        assertEquals("", selectAbi(listOf("mips64")))
    }

    @Test
    fun `空架构列表返回空串且分包档位为arm64与v7a`() {
        assertEquals("", selectAbi(emptyList()))
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), SHIPPED_ABIS)
    }

    // ---- parseLatestReleaseTag ----

    @Test
    fun `从href形式解析出最新release的tag`() {
        assertEquals("v1.6.0", parseLatestReleaseTag(LATEST_PAGE_HTML, OWNER, REPO))
    }

    @Test
    fun `实体引号包裹的release链接同样命中`() {
        // &quot; 作为属性引号时，捕获到的尾随实体由实现清除
        assertEquals("v1.6.0", parseLatestReleaseTag(ENTITY_QUOTED_HTML, OWNER, REPO))
    }

    @Test
    fun `无tag链接时返回空串`() {
        assertEquals("", parseLatestReleaseTag(NO_TAG_HTML, OWNER, REPO))
        assertEquals("", parseLatestReleaseTag("", OWNER, REPO))
    }

    @Test
    fun `只匹配传入的owner与repo`() {
        assertEquals("v1.6.0", parseLatestReleaseTag(CROSS_REPO_HTML, OWNER, REPO))
        // 其他 owner 的同名仓库只命中它自己的链接
        assertEquals("v9.9.9", parseLatestReleaseTag(CROSS_REPO_HTML, "someoneelse", REPO))
        // owner/repo 不匹配（含参数调换）时不误配
        assertEquals("", parseLatestReleaseTag(CROSS_REPO_HTML, OWNER, "nonexistent-repo"))
        assertEquals("", parseLatestReleaseTag(CROSS_REPO_HTML, REPO, OWNER))
    }

    // ---- extractApkDownloadPaths ----

    @Test
    fun `提取全部apk下载路径并保持文档顺序且忽略源码zip`() {
        val paths = extractApkDownloadPaths(EXPANDED_ASSETS_HTML)
        // 提取层只按 releases/download/**.apk 抓取：debug 包也在结果里，过滤发生在 findMatchingAsset
        assertEquals(listOf(ARM64_RELEASE_PATH, V7A_RELEASE_PATH, ARM64_DEBUG_PATH), paths)
        assertFalse(paths.any { it.endsWith(".zip") })
        assertTrue(paths.none { it == SOURCE_ZIP_PATH })
        assertTrue(paths.all { it.endsWith(".apk") })
    }

    @Test
    fun `仅含release资产的页面提取出两个apk`() {
        assertEquals(
            listOf(ARM64_RELEASE_PATH, V7A_RELEASE_PATH),
            extractApkDownloadPaths(RELEASE_ONLY_ASSETS_HTML),
        )
    }

    @Test
    fun `无apk链接的页面提取结果为空`() {
        assertTrue(extractApkDownloadPaths(HTML_WITHOUT_APK).isEmpty())
        assertTrue(extractApkDownloadPaths("").isEmpty())
    }

    // ---- findMatchingAsset ----

    @Test
    fun `匹配arm64的release资产并归一化版本与补全绝对地址`() {
        val info = findMatchingAsset("arm64-v8a", "v1.5.0", EXPANDED_ASSETS_HTML)
        assertNotNull(info)
        assertEquals(ARM64_RELEASE_ASSET, info!!.assetName)
        // 版本号去掉 v 前缀
        assertEquals("1.5.0", info.versionName)
        // 相对路径补全为 GitHub 绝对地址
        assertEquals("https://github.com$ARM64_RELEASE_PATH", info.downloadUrl)
        assertTrue(info.downloadUrl.startsWith("https://github.com/"))
        assertTrue(info.downloadUrl.endsWith(ARM64_RELEASE_ASSET))
        assertEquals(0L, info.sizeBytes)
    }

    @Test
    fun `匹配v7a设备对应的release资产`() {
        val info = findMatchingAsset("armeabi-v7a", "v1.5.0", EXPANDED_ASSETS_HTML)
        assertNotNull(info)
        assertEquals(V7A_RELEASE_ASSET, info!!.assetName)
        assertEquals("1.5.0", info.versionName)
        assertTrue(info.downloadUrl.endsWith(V7A_RELEASE_ASSET))
    }

    @Test
    fun `资产名版本与release版本不一致时不匹配`() {
        assertNull(findMatchingAsset("arm64-v8a", "1.4.0", EXPANDED_ASSETS_HTML))
        assertNull(findMatchingAsset("arm64-v8a", "v1.6.0", EXPANDED_ASSETS_HTML))
    }

    @Test
    fun `仅存在debug包时不匹配`() {
        assertNull(findMatchingAsset("arm64-v8a", "v1.5.0", DEBUG_ONLY_ASSETS_HTML))
        assertNull(findMatchingAsset("armeabi-v7a", "v1.5.0", DEBUG_ONLY_ASSETS_HTML))
    }

    @Test
    fun `当前ABI为空白时不匹配`() {
        // selectAbi 在设备架构不在分包档位时返回空串，调用方直接视为无适配包
        assertNull(findMatchingAsset("", "v1.5.0", EXPANDED_ASSETS_HTML))
        assertNull(findMatchingAsset("   ", "v1.5.0", EXPANDED_ASSETS_HTML))
    }

    @Test
    fun `设备架构无对应资产时不匹配`() {
        // 本仓库不产出 x86/x86_64 资产
        assertNull(findMatchingAsset("x86_64", "v1.5.0", EXPANDED_ASSETS_HTML))
        assertNull(findMatchingAsset("x86", "v1.5.0", EXPANDED_ASSETS_HTML))
    }

    @Test
    fun `空资产页面不匹配`() {
        assertNull(findMatchingAsset("arm64-v8a", "v1.5.0", ""))
        assertNull(findMatchingAsset("arm64-v8a", "v1.5.0", HTML_WITHOUT_APK))
    }

    @Test
    fun `存在debug包时不会误选debug资产`() {
        val info = findMatchingAsset("arm64-v8a", "v1.5.0", EXPANDED_ASSETS_HTML)
        assertNotNull(info)
        assertFalse(info!!.assetName.contains("-debug"))
        assertFalse(info.assetName.equals(ARM64_DEBUG_ASSET))
        assertTrue(info.assetName.endsWith("-release.apk"))
        assertEquals(ARM64_RELEASE_ASSET, info.assetName)
    }

    @Test
    fun `发布版本号为空时不匹配`() {
        assertNull(findMatchingAsset("arm64-v8a", "", EXPANDED_ASSETS_HTML))
        assertNull(findMatchingAsset("arm64-v8a", "   ", EXPANDED_ASSETS_HTML))
        assertNull(findMatchingAsset("arm64-v8a", "v", EXPANDED_ASSETS_HTML))
    }
}

// ---- fixture：仓库标识与资产名 ----

private const val OWNER = "gbandszxc"
private const val REPO = "open-burnin-tool"

private const val ARM64_RELEASE_ASSET = "open-burnin-tool-v1.5.0-arm64-v8a-release.apk"
private const val V7A_RELEASE_ASSET = "open-burnin-tool-v1.5.0-armeabi-v7a-release.apk"
private const val ARM64_DEBUG_ASSET = "open-burnin-tool-v1.5.0-arm64-v8a-debug.apk"

private const val ASSET_DIR = "/$OWNER/$REPO/releases/download/v1.5.0/"
private const val ARM64_RELEASE_PATH = "$ASSET_DIR$ARM64_RELEASE_ASSET"
private const val V7A_RELEASE_PATH = "$ASSET_DIR$V7A_RELEASE_ASSET"
private const val ARM64_DEBUG_PATH = "$ASSET_DIR$ARM64_DEBUG_ASSET"
private const val SOURCE_ZIP_PATH = "/$OWNER/$REPO/archive/refs/tags/v1.5.0.zip"

// ---- fixture：页面 HTML（贴近 GitHub 实际结构，路径已用 curl 核实） ----

/** `/releases/expanded_assets/v1.5.0` 资产列表：两个 release APK + 同架构 debug APK + 源码 zip。 */
private val EXPANDED_ASSETS_HTML = """
    <div class="Box-row">
      <a href="$ARM64_RELEASE_PATH" rel="nofollow">$ARM64_RELEASE_ASSET</a>
    </div>
    <div class="Box-row">
      <a href="$V7A_RELEASE_PATH" rel="nofollow">$V7A_RELEASE_ASSET</a>
    </div>
    <div class="Box-row">
      <a href="$ARM64_DEBUG_PATH" rel="nofollow">$ARM64_DEBUG_ASSET</a>
    </div>
    <div class="Box-row">
      <a href="$SOURCE_ZIP_PATH" rel="nofollow">Source code (zip)</a>
    </div>
""".trimIndent()

/** 正常发布形态：只挂两个 release APK 与源码 zip。 */
private val RELEASE_ONLY_ASSETS_HTML = """
    <div class="Box-row">
      <a href="$ARM64_RELEASE_PATH" rel="nofollow">$ARM64_RELEASE_ASSET</a>
    </div>
    <div class="Box-row">
      <a href="$V7A_RELEASE_PATH" rel="nofollow">$V7A_RELEASE_ASSET</a>
    </div>
    <div class="Box-row">
      <a href="$SOURCE_ZIP_PATH" rel="nofollow">Source code (zip)</a>
    </div>
""".trimIndent()

/** 同版本同架构但只有调试包：不得被当成可安装更新。 */
private val DEBUG_ONLY_ASSETS_HTML = """
    <div class="Box-row">
      <a href="$ARM64_DEBUG_PATH" rel="nofollow">$ARM64_DEBUG_ASSET</a>
    </div>
""".trimIndent()

/** 无任何 APK 链接的页面。 */
private const val HTML_WITHOUT_APK = "<div class=\"Box-row\">No assets</div>"

/** `/releases/latest` 未重定向时正文里带 release 链接的页面片段。 */
private val LATEST_PAGE_HTML = """
    <html>
    <body>
    <a href="/$OWNER/$REPO/releases/tag/v1.6.0">v1.6.0</a>
    </body>
    </html>
""".trimIndent()

/** 链接被 `&quot;` 实体引号包裹、并以标签结束符收尾的页面片段。 */
private val ENTITY_QUOTED_HTML = """
    <div data-release-href=&quot;/$OWNER/$REPO/releases/tag/v1.6.0&quot;</div>
""".trimIndent()

/** 尚无 Release、正文无 tag 链接的页面。 */
private const val NO_TAG_HTML = "<html><body><p>No releases yet</p></body></html>"

/** 含其他 owner / 其他仓库链接的页面：验证 owner、repo 由参数注入后不误配。 */
private val CROSS_REPO_HTML = """
    <a href="/someoneelse/$REPO/releases/tag/v9.9.9">fork</a>
    <a href="/$OWNER/other-repo/releases/tag/v8.8.8">other</a>
    <a href="/$OWNER/$REPO/releases/tag/v1.6.0">target</a>
""".trimIndent()
