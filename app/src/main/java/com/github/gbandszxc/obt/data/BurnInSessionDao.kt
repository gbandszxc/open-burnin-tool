package com.github.gbandszxc.obt.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 煲机会话 DAO：插入、更新进度/状态、按开始时间倒序的分页查询、聚合汇总与清除。 */
@Dao
interface BurnInSessionDao {

    /** 新建会话，返回自增 id。 */
    @Insert
    suspend fun insert(session: BurnInSession): Long

    /** 更新已完成秒数（每 tick 或定期持久化时调用，对应原版每 90 秒落一次盘）。 */
    @Query(
        "UPDATE burn_in_sessions SET completedSeconds = :completedSeconds, " +
            "lastUpdatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateProgress(id: Long, completedSeconds: Long, updatedAt: Long)

    /** 更新会话状态（暂停/恢复/完成/放弃）。 */
    @Query(
        "UPDATE burn_in_sessions SET status = :status, " +
            "lastUpdatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateStatus(id: Long, status: SessionStatus, updatedAt: Long)

    /** 单个会话（续播时恢复进度）。 */
    @Query("SELECT * FROM burn_in_sessions WHERE id = :id")
    suspend fun getById(id: Long): BurnInSession?

    /**
     * 分页取一页会话，按开始时间倒序（与全量列表同序，id 作同时刻的稳定次序）。
     * 记录页首屏与滚动追加均走这里，避免一次性全量加载。
     */
    @Query(
        "SELECT * FROM burn_in_sessions " +
            "ORDER BY startedAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun queryPage(limit: Int, offset: Int): List<BurnInSession>

    /**
     * 会话总数（聚合）。Room 在本表任何增删改后都会重查并重发（含数值不变的进度更新），
     * 记录页借此感知数据变化：既是小结「会话次数」，也兼作分页列表的重载信号。
     */
    @Query("SELECT COUNT(*) FROM burn_in_sessions")
    fun observeSessionCount(): Flow<Int>

    /** 累计煲机秒数：所有会话 completedSeconds 之和（含中途放弃会话已煲的部分）。 */
    @Query("SELECT COALESCE(SUM(completedSeconds), 0) FROM burn_in_sessions")
    fun observeTotalCompletedSeconds(): Flow<Long>

    /** 累计煲机秒数的一次性读取。 */
    @Query("SELECT COALESCE(SUM(completedSeconds), 0) FROM burn_in_sessions")
    suspend fun totalCompletedSeconds(): Long

    /**
     * 指定方案规模（presetHours + plannedSeconds 共同锁定）下最近一条「可续播」会话：
     * 状态为 RUNNING/PAUSED 且已完成秒数大于 0；ABANDONED/COMPLETED 不算可续播。
     * 无匹配返回 null。供 UI 查询「继续」入口（方案续播支持）。
     */
    @Query(
        "SELECT * FROM burn_in_sessions " +
            // 存库大小写由 TypeConverter 决定（SessionStatusConverter 以小写存库），
            // SQL 字面量比较必须大小写不敏感（UPPER），否则查询恒空、续播入口永不出现。
            "WHERE UPPER(status) IN ('RUNNING', 'PAUSED') AND completedSeconds > 0 " +
            "AND presetHours = :presetHours AND plannedSeconds = :plannedSeconds " +
            "ORDER BY startedAt DESC, id DESC LIMIT 1",
    )
    suspend fun latestResumable(presetHours: Int, plannedSeconds: Long): BurnInSession?

    /**
     * 把指定方案规模（presetHours + plannedSeconds 共同锁定）下所有历史 RUNNING/PAUSED
     * 检查点（无论有无进度）批量置为 ABANDONED：开始新会话前调用，保证同一方案任意时刻
     * 至多保留一个可续检查点——无论「继续」（旧检查点被新会话取代）还是「全新开始」（旧检查点作废），
     * 旧 RUNNING/PAUSED 检查点行（含起播即杀后台留下的零进度行）都不再无限累积、
     * 方案卡不再显示过期的「上次进度」。
     * 置为 ABANDONED 后历史记录仍可见（记录页显示"已放弃"），只是不再进入续播查询。
     *
     * 大小写坑：存库值由 SessionStatusConverter 决定（小写），但 SQL 字面量比较必须经
     * UPPER 做大小写不敏感匹配，否则依赖存库格式、口径脆弱（与 [latestResumable] 同一口径）；
     * 写入的 'abandoned' 字面量与 Converter 的小写存库格式一致。
     */
    @Query(
        "UPDATE burn_in_sessions SET status = 'abandoned', lastUpdatedAt = :now " +
            "WHERE UPPER(status) IN ('RUNNING', 'PAUSED') " +
            "AND presetHours = :presetHours AND plannedSeconds = :plannedSeconds",
    )
    suspend fun abandonResumableByPlan(presetHours: Int, plannedSeconds: Long, now: Long)

    /** 清空全部会话（清除重置）。累计统计同表，删除后随之归零。 */
    @Query("DELETE FROM burn_in_sessions")
    suspend fun clearAll()
}
