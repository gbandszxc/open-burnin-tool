package com.github.gbandszxc.obt.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地音乐音轨：用户导入的应用私有目录音频文件记录（表 local_tracks）。
 *
 * 职责边界：本表只存「元数据 + 相对文件名」，真实音频文件保存在
 * `context.filesDir/burn_music/` 内（应用私有目录，卸载即清理），
 * 文件名与记录的映射、防越权删除逻辑见 [TrackRepository]。
 *
 * @property id 自增主键（[BurnPhase.localTrackId][com.github.gbandszxc.obt.domain.model.BurnPhase] 引用此 id）。
 * @property displayName 展示名（导入时的原始文件名去扩展名前的完整名，UI 显示与播放状态用）。
 * @property fileName 应用私有目录内的文件名（uuid + 原扩展名），播放时经
 *   [TrackRepository.playbackPath] 解析为绝对路径。
 * @property durationMs 音频时长（毫秒），由 MediaMetadataRetriever 读取；不可读时为 null。
 * @property sizeBytes 文件大小（字节）；未知时为 null。
 * @property addedAt 导入时间（epoch 毫秒），列表按其倒序。
 */
@Entity(tableName = "local_tracks")
data class LocalTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val displayName: String,
    val fileName: String,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val addedAt: Long,
)
