package com.github.gbandszxc.obt.data

import android.util.Log
import com.github.gbandszxc.obt.domain.model.BurnPlan
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import kotlinx.coroutines.flow.Flow

/**
 * 方案总时长向上取整的小时数（会话行的 presetHours 字段语义）。
 * 顶层 internal：与 [BurnInRepository.latestResumableSession] 的方案匹配共用同一推导，JVM 可测。
 */
internal fun presetHoursOf(plan: BurnPlan): Int =
    ((plan.totalSeconds + SECONDS_PER_HOUR - 1) / SECONDS_PER_HOUR).toInt()

/** 秒/小时换算基数。 */
private const val SECONDS_PER_HOUR: Long = 3_600L

/**
 * 自由煲机会话的音效快照（会话行 soundSourceId / soundLabel 两列的取值依据）。
 *
 * @property sourceId 音效的原版音源编号（[SoundSource.legacySoundId]）；本地音乐音源
 *   恒为 [SoundSource.LOCAL_TRACK] 的 7，供历史页回显「所用音效」。
 * @property localTrackId 本地音乐音源时歌单首个曲目 id（内置合成音源为 null）。
 *   仅作展示层解析线索；展示名由播放层在会话开始时查库解析并快照（见
 *   [sessionSoundInfo] 的 KDoc），不在此携带，保持本类型为纯派生结果。
 */
internal data class SessionSoundInfo(
    val sourceId: Int,
    val localTrackId: Long?,
)

/**
 * 由方案派生会话应记录的音效快照：仅自由煲机（planId 以 `quick_` 开头，[BurnPlans.quick]
 * 产出的单阶段方案）非 null；方案煲机（classic_/custom_）返回 null，会话两列保持 null
 * （历史页不展示音效）。
 *
 * 取值语义：
 * - 阶段 [com.github.gbandszxc.obt.domain.model.BurnPhase.localTrackIds] 非空（本地音乐音源）：
 *   sourceId = [SoundSource.LOCAL_TRACK] 的 legacySoundId（7），localTrackId = 首个曲目 id；
 * - 否则（内置合成音源）：sourceId = 阶段音源的 legacySoundId，localTrackId = null。
 *
 * 展示名不在此解析：quick 方案是纯领域对象，JVM 可测；曲目展示名由播放层
 * （PlaybackController.startInternal）在会话开始时经 TrackRepository 查库快照落库——
 * 曲目之后可能被删除，必须存名字而非只存 id。
 *
 * 注意：planId 前缀判定用 `startsWith("quick_")`，与续播匹配的整串正则口径不同——
 * 此处只区分「自由煲机 / 方案煲机」两族，避免把 classic_120h 等误判为 quick 变体。
 * 顶层 internal：纯函数无状态，JVM 单测覆盖（见 BurnInRepositoryTest）。
 */
internal fun sessionSoundInfo(plan: BurnPlan): SessionSoundInfo? {
    if (!plan.id.startsWith("quick_")) return null
    val phase = plan.phases.first()
    val trackIds = phase.localTrackIds
    return if (trackIds.isNotEmpty()) {
        SessionSoundInfo(sourceId = SoundSource.LOCAL_TRACK.legacySoundId, localTrackId = trackIds.first())
    } else {
        SessionSoundInfo(sourceId = phase.soundSource.legacySoundId, localTrackId = null)
    }
}

/**
 * 煲机数据仓库：会话生命周期与进度落库的唯一入口。
 * Application 级单例（见 [AppContainer]），不引入 Hilt。
 */
class BurnInRepository(private val dao: BurnInSessionDao) {

    /** 会话总数流：小结「会话次数」数据源；表任何增删改都会重发，兼作记录页分页重载信号。 */
    val sessionCount: Flow<Int> = dao.observeSessionCount()

    /** 累计煲机秒数（所有会话之和）。 */
    val totalCompletedSeconds: Flow<Long> = dao.observeTotalCompletedSeconds()

    /**
     * 新建并开始一次会话；presetHours 由方案总时长向上取整推导。
     * [initialCompletedSeconds] 为续播起点（方案续播支持）：会话行一开始即写入该已完成秒数，
     * 使其立刻成为可续播记录（续播再次中断后仍能从最新进度继续）。默认 0 保持新建语义。
     *
     * 音效快照（自由煲机历史回显）：[soundSourceId] / [soundLabel] 为会话所用音效，
     * 由调用方（PlaybackController.startInternal）经 [sessionSoundInfo] 派生后传入
     * （本地音乐音源的展示名也由其查库解析）；默认双 null = 方案煲机 / 旧数据语义，
     * 既有调用点零改动。
     * 返回自增 id。
     */
    suspend fun startSession(
        plan: BurnPlan,
        startedAtMillis: Long,
        initialCompletedSeconds: Long = 0L,
        soundSourceId: Int? = null,
        soundLabel: String? = null,
    ): Long {
        return dao.insert(
            BurnInSession(
                presetHours = presetHoursOf(plan),
                plannedSeconds = plan.totalSeconds,
                completedSeconds = initialCompletedSeconds.coerceIn(0L, plan.totalSeconds),
                startedAt = startedAtMillis,
                lastUpdatedAt = startedAtMillis,
                status = SessionStatus.RUNNING,
                soundSourceId = soundSourceId,
                soundLabel = soundLabel,
            ),
        )
    }

    /**
     * 查询某方案最近一条「可续播」会话（方案续播支持）：status IN (RUNNING, PAUSED)
     * 且 completedSeconds > 0 的最近一条；ABANDONED/COMPLETED 不算可续播。无则返回 null。
     *
     * 会话表未冗余存 planId，这里由 [planId]（如 quick_8h / classic_120h / custom_36h）
     * 还原方案的 (presetHours, plannedSeconds) 再精确匹配——两项同时相等即视为同一方案
     * （同小时数的 quick 与 custom 规模一致，续播起点语义等价）。
     * 供 UI 查询「继续」入口；DAO 不可 JVM 直测，匹配语义由 fake DAO 层单测覆盖
     * （见 BurnInRepositoryTest）。
     */
    suspend fun latestResumableSession(planId: String): BurnInSession? {
        val presetHours = presetHoursOfPlanId(planId) ?: return null
        return dao.latestResumable(presetHours.first, presetHours.second)
    }

    /**
     * 开始新会话前作废同方案旧的可续检查点：该方案下所有 RUNNING/PAUSED 且
     * completedSeconds > 0 的会话行批量置为 ABANDONED——历史记录仍可见（记录页显示
     * "已放弃"），但不再出现在 [latestResumableSession] 的续播查询里，保证同一方案
     * 任意时刻至多保留一个可续检查点。方案口径与 [latestResumableSession] 一致：
     * [presetHoursOf] + [BurnPlan.totalSeconds] 精确匹配。
     *
     * 异常吞掉仅记日志：检查点治理属best-effort，失败不应阻断起播主流程
     * （调用方 PlaybackController.startInternal 在插入新会话之前调用本方法）。
     */
    suspend fun abandonResumableSessions(plan: BurnPlan, nowMillis: Long) {
        try {
            dao.abandonResumableByPlan(
                presetHours = presetHoursOf(plan),
                plannedSeconds = plan.totalSeconds,
                now = nowMillis,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "作废旧可续检查点失败（不影响起播）", t)
        }
    }

    /** 落盘已完成秒数（对应原版每 90 秒持久化一次剩余秒）。 */
    suspend fun recordProgress(sessionId: Long, completedSeconds: Long, updatedAtMillis: Long) {
        dao.updateProgress(sessionId, completedSeconds, updatedAtMillis)
    }

    /**
     * 分页取一页会话（按开始时间倒序）：记录页首屏与滚动追加调用，
     * 替代全量列表的一次性加载。
     */
    suspend fun sessionPage(limit: Int, offset: Int): List<BurnInSession> =
        dao.queryPage(limit = limit, offset = offset)

    /**
     * 清除重置：删除全部煲机会话。累计煲机秒数/会话次数与列表同表（burn_in_sessions），
     * 删除后由各自 Flow 重发归零，UI 无需额外复位。
     * 异常吞掉仅记日志（与 [abandonResumableSessions] 同口径）：失败时数据保持原样
     * （宁可不清也不半清），不向调用方抛出、不阻断 UI。
     */
    suspend fun clearAllSessions() {
        try {
            dao.clearAll()
        } catch (t: Throwable) {
            Log.w(TAG, "清除全部煲机记录失败（数据保持原样）", t)
        }
    }

    /** 更新会话状态（暂停/恢复/完成/放弃）。 */
    suspend fun updateStatus(sessionId: Long, status: SessionStatus, updatedAtMillis: Long) {
        dao.updateStatus(sessionId, status, updatedAtMillis)
    }

    /** 读取单个会话（进程被杀后续播恢复，对应原版 f() 从 DB 恢复剩余秒）。 */
    suspend fun getSession(sessionId: Long): BurnInSession? = dao.getById(sessionId)

    private companion object {
        const val TAG = "BurnInRepository"

        /** planId 形如 "classic_120h" / "quick_8h" / "custom_36h"（见 [BurnPlans] 各工厂）。 */
        val PLAN_ID_REGEX = Regex("^(classic|quick|custom)_(\\d+)h$")

        /** 由 planId 还原方案并推导 (presetHours, plannedSeconds)；非法/未知 planId 返回 null。
         *  classic 仅接受原版 120 小时（BurnPlans 只会产生 classic_120h 这一种 id）。 */
        fun presetHoursOfPlanId(planId: String): Pair<Int, Long>? {
            val match = PLAN_ID_REGEX.matchEntire(planId) ?: return null
            val hours = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val plan = when (match.groupValues[1]) {
                "classic" ->
                    if (hours * SECONDS_PER_HOUR == BurnPlans.CLASSIC_TOTAL_SECONDS) {
                        BurnPlans.CLASSIC
                    } else {
                        return null
                    }
                "quick" -> BurnPlans.quick(hours)
                else -> BurnPlans.custom(hours)
            }
            return presetHoursOf(plan) to plan.totalSeconds
        }
    }
}

