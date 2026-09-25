package com.github.gbandszxc.obt.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 从内容 [Uri] 的展示名解析音频文件扩展名：合法扩展名返回小写 `".xxx"`，
 * 取不到或不合法（无扩展名/隐藏文件/含非 ASCII 字母数字字符/超长）一律回退 `".audio"`。
 *
 * 纯逻辑，JVM 单测覆盖（见 TrackRepositoryExtensionTest）。
 */
internal fun resolveAudioExtension(displayName: String?): String {
    if (displayName.isNullOrBlank()) return FALLBACK_EXTENSION
    val dot = displayName.lastIndexOf('.')
    if (dot <= 0 || dot == displayName.length - 1) return FALLBACK_EXTENSION
    val ext = displayName.substring(dot + 1)
    if (ext.length > MAX_EXTENSION_LENGTH) return FALLBACK_EXTENSION
    // 仅接受 ASCII 字母数字，拒绝路径分隔符、空白与 CJK 等「扩展名」，
    // 避免拼出异常文件名；文件系统不依赖扩展名解码内容，回退 .audio 无损
    if (!ext.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return FALLBACK_EXTENSION
    return ".$ext".lowercase()
}

/** [resolveAudioExtension] 的兜底扩展名与超长上限。 */
private const val FALLBACK_EXTENSION = ".audio"
private const val MAX_EXTENSION_LENGTH = 8

/**
 * 本地音轨仓库：本地音乐文件导入、查询与移除的唯一入口（Application 级单例，见 [AppContainer]）。
 *
 * 存储模型：
 * - 音频文件统一拷贝进应用私有目录 `filesDir/burn_music/`，文件名为 `uuid + 原扩展名`
 *   （与用户原名解耦，规避重名/特殊字符/路径穿越）；记录存 Room `local_tracks` 表；
 * - 所有 IO 固定在 [Dispatchers.IO]；拷贝/删除失败均捕获记日志、对外不抛（导入失败返回 null），
 *   调用方（UI 层）据此提示即可，应用不会崩溃；
 * - 删除做了防越权校验：只允许删除 [MUSIC_DIR] 目录内的文件。
 *
 * @param appContext Application Context（仅用于私有目录定位与 ContentResolver）。
 * @param dao 本地音轨 DAO。
 */
class TrackRepository(private val appContext: Context, private val dao: LocalTrackDao) {

    /** 全部音轨，按加入时间倒序。 */
    val tracks: Flow<List<LocalTrack>> = dao.observeAll()

    /**
     * 从内容 [Uri] 导入音频文件：拷贝流到 `filesDir/burn_music/`（uuid 文件名，扩展名取自
     * [OpenableColumns.DISPLAY_NAME]），用 MediaMetadataRetriever 读取时长，
     * 插入记录并返回完整实体。
     *
     * 打不开流 / 拷贝中断 / 建目录失败等任何异常都记日志并返回 null，不向调用方抛出。
     */
    suspend fun importFromUri(uri: Uri): LocalTrack? = withContext(Dispatchers.IO) {
        try {
            importInternal(uri)
        } catch (t: Throwable) {
            Log.e(TAG, "本地音轨导入失败：$uri", t)
            null
        }
    }

    /** 按 id 查询单条音轨（播放链路切阶段时解析本地音乐文件用）。 */
    suspend fun getById(id: Long): LocalTrack? = dao.getById(id)

    /**
     * 移除音轨：先删文件（防越权：只允许 [MUSIC_DIR] 目录内的文件）再删记录行。
     * 文件删除失败仅记日志、仍删记录（避免留下指向无效数据的幽灵条目）；
     * 行删除失败同样只记日志，不向调用方抛出。
     */
    suspend fun delete(track: LocalTrack) {
        val dir = musicDir()
        val file = File(dir, track.fileName)
        try {
            // canonicalPath 归一化后做前缀校验，拦截 "../" 与符号链接式的越权文件名
            val dirPath = dir.canonicalPath + File.separator
            if (file.canonicalPath.startsWith(dirPath)) {
                if (!file.delete() && file.exists()) {
                    Log.w(TAG, "音轨文件删除失败（文件仍在）：${track.fileName}")
                }
            } else {
                Log.w(TAG, "拒绝删除越权路径的音轨文件：${track.fileName}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "音轨文件删除异常：${track.fileName}", t)
        }
        try {
            dao.delete(track)
        } catch (t: Throwable) {
            Log.e(TAG, "音轨记录删除失败（id=${track.id}）", t)
        }
    }

    /** 解析音轨文件绝对路径（供播放器 setDataSource 用；文件可能已被清理，调用方需容错）。 */
    fun playbackPath(track: LocalTrack): String = File(musicDir(), track.fileName).absolutePath

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private suspend fun importInternal(uri: Uri): LocalTrack {
        val displayName = queryDisplayName(appContext.contentResolver, uri) ?: DEFAULT_DISPLAY_NAME

        val dir = musicDir()
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("音轨目录创建失败：$dir")
        }
        // uuid 文件名与用户输入解耦：无重名冲突，也无路径穿越风险
        val target = File(dir, UUID.randomUUID().toString() + resolveAudioExtension(displayName))
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法打开内容流：$uri")
        if (target.length() <= 0L) throw IOException("导入内容为空：$uri")

        val durationMs = readDurationMs(target)
        val record = LocalTrack(
            displayName = displayName,
            fileName = target.name,
            durationMs = durationMs,
            sizeBytes = target.length(),
            addedAt = System.currentTimeMillis(),
        )
        val rowId = dao.insert(record)
        Log.i(TAG, "本地音轨导入成功：$displayName → ${target.name}（duration=$durationMs ms）")
        return record.copy(id = rowId)
    }

    /** 查询内容 [Uri] 的展示名（扩展名解析与展示都用它）；查询失败/列缺失不致命，返回 null。 */
    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "内容展示名查询失败（不影响导入）：$uri", t)
            null
        }
    }

    /**
     * 用 MediaMetadataRetriever 读取音频时长（毫秒）；不可解码/读取异常一律返回 null。
     * 注意不用 use{}：MediaMetadataRetriever 实现 AutoCloseable 仅在 API 29+，minSdk 26 下手动 release。
     */
    private fun readDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "音频时长读取失败（记录为未知时长）：${file.name}", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun musicDir(): File = File(appContext.filesDir, MUSIC_DIR)

    private companion object {
        const val TAG = "TrackRepository"

        /** 私有音频目录名（相对 filesDir）。 */
        const val MUSIC_DIR = "burn_music"

        /** 展示名取不到时的兜底文案。 */
        const val DEFAULT_DISPLAY_NAME = "未命名音频"
    }
}
