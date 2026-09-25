package com.github.gbandszxc.obt.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** 会话状态：running 进行中 / paused 暂停 / completed 完成 / abandoned 用户放弃。 */
enum class SessionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    ABANDONED,
}

/** [SessionStatus] 以小写字符串存库（running/paused/completed/abandoned）。 */
class SessionStatusConverter {
    @TypeConverter
    fun statusToString(status: SessionStatus): String = status.name.lowercase()

    @TypeConverter
    fun stringToStatus(value: String): SessionStatus =
        SessionStatus.valueOf(value.uppercase())
}

/**
 * 一次煲机会话（对应原 App 落 SQLite 的会话持久化职责，
 * 原版每 90 秒持久化一次剩余秒）。
 *
 * 只存已完成秒数，剩余秒由 plannedSeconds - completedSeconds 推导，
 * 避免原版「存剩余秒 + 墙钟漂移事后校正」的双份状态。
 *
 * @property id 自增主键。
 * @property presetHours 预设小时数（原版标准方案为 120；快捷预设 2/8/16/24/48/72；自定义任意正整数）。
 * @property plannedSeconds 计划总时长（秒）。
 * @property completedSeconds 已完成秒数（单调递增）。
 * @property startedAt 开始时间（epoch 毫秒）。
 * @property lastUpdatedAt 最近一次进度/状态更新时间（epoch 毫秒）。
 * @property status 会话状态。
 */
@Entity(tableName = "burn_in_sessions")
data class BurnInSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val presetHours: Int,
    val plannedSeconds: Long,
    val completedSeconds: Long = 0L,
    val startedAt: Long,
    val lastUpdatedAt: Long,
    val status: SessionStatus = SessionStatus.RUNNING,
)
