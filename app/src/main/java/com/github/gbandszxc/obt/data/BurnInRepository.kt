package com.github.gbandszxc.obt.data

import android.util.Log
import com.github.gbandszxc.obt.domain.model.BurnPlan
import com.github.gbandszxc.obt.domain.model.BurnPlans
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
     * 返回自增 id。
     */
    suspend fun startSession(
        plan: BurnPlan,
        startedAtMillis: Long,
        initialCompletedSeconds: Long = 0L,
    ): Long {
        return dao.insert(
            BurnInSession(
                presetHours = presetHoursOf(plan),
                plannedSeconds = plan.totalSeconds,
                completedSeconds = initialCompletedSeconds.coerceIn(0L, plan.totalSeconds),
                startedAt = startedAtMillis,
                lastUpdatedAt = startedAtMillis,
                status = SessionStatus.RUNNING,
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

