package com.github.gbandszxc.obt.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 本地音轨 DAO：插入、按加入时间倒序列表、按 id 查询、删除。 */
@Dao
interface LocalTrackDao {

    /** 新增音轨记录，返回自增 id。 */
    @Insert
    suspend fun insert(track: LocalTrack): Long

    /** 全部音轨，按加入时间倒序（同毫秒导入时按自增 id 倒序兜底）。 */
    @Query("SELECT * FROM local_tracks ORDER BY addedAt DESC, id DESC")
    fun observeAll(): Flow<List<LocalTrack>>

    /** 按 id 查询单条音轨（播放链路切换到本地音乐阶段时解析文件用，每次切换只查一次）。 */
    @Query("SELECT * FROM local_tracks WHERE id = :id")
    suspend fun getById(id: Long): LocalTrack?

    /** 删除音轨记录（文件本体由 [TrackRepository.delete] 负责先行删除）。 */
    @Delete
    suspend fun delete(track: LocalTrack)
}
