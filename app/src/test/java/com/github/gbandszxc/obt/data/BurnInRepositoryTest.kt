package com.github.gbandszxc.obt.data

import com.github.gbandszxc.obt.domain.model.BurnPlans
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BurnInRepository] 方案续播查询语义测试（fake DAO 层面：Room DAO 无法 JVM 直测，
 * 用内存版 [BurnInSessionDao] 验证 latestResumableSession 的状态/进度过滤与 planId 匹配，
 * 以及 startSession 把续播起点写入初始 completedSeconds）。
 */
class BurnInRepositoryTest {

    // ------------------------------------------------------------------
    // fake DAO：内存实现，查询语义与 SQL（ORDER BY startedAt DESC, id DESC LIMIT 1）一致
    // ------------------------------------------------------------------

    private class FakeBurnInSessionDao : BurnInSessionDao {
        val rows = mutableListOf<BurnInSession>()
        private var nextId = 1L

        override suspend fun insert(session: BurnInSession): Long {
            val id = nextId++
            rows += session.copy(id = id)
            return id
        }

        override suspend fun updateProgress(id: Long, completedSeconds: Long, updatedAt: Long) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(completedSeconds = completedSeconds, lastUpdatedAt = updatedAt)
        }

        override suspend fun updateStatus(id: Long, status: SessionStatus, updatedAt: Long) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(status = status, lastUpdatedAt = updatedAt)
        }

        override suspend fun getById(id: Long): BurnInSession? = rows.firstOrNull { it.id == id }

        // 对应 SQL：ORDER BY startedAt DESC, id DESC LIMIT :limit OFFSET :offset
        override suspend fun queryPage(limit: Int, offset: Int): List<BurnInSession> =
            rows.sortedWith(
                compareByDescending<BurnInSession> { it.startedAt }.thenByDescending { it.id },
            ).drop(offset).take(limit)

        override fun observeSessionCount(): Flow<Int> = flow { emit(rows.size) }

        override suspend fun clearAll() {
            rows.clear()
        }

        override fun observeTotalCompletedSeconds(): Flow<Long> =
            flow { emit(rows.sumOf { it.completedSeconds }) }

        override suspend fun totalCompletedSeconds(): Long = rows.sumOf { it.completedSeconds }

        // 对应 SQL：status IN (RUNNING, PAUSED) AND completedSeconds > 0
        //   AND presetHours = :x AND plannedSeconds = :y ORDER BY startedAt DESC, id DESC LIMIT 1
        override suspend fun latestResumable(presetHours: Int, plannedSeconds: Long): BurnInSession? =
            rows.asSequence()
                .filter {
                    (it.status == SessionStatus.RUNNING || it.status == SessionStatus.PAUSED) &&
                        it.completedSeconds > 0L &&
                        it.presetHours == presetHours &&
                        it.plannedSeconds == plannedSeconds
                }
                .maxWithOrNull(compareBy({ it.startedAt }, { it.id }))

        // 对应 SQL：UPDATE ... SET status = 'abandoned' WHERE UPPER(status) IN ('RUNNING','PAUSED')
        //   AND completedSeconds > 0 AND presetHours = :x AND plannedSeconds = :y（全量更新，无 LIMIT）
        override suspend fun abandonResumableByPlan(presetHours: Int, plannedSeconds: Long, now: Long) {
            rows.withIndex()
                .filter { (_, s) ->
                    (s.status == SessionStatus.RUNNING || s.status == SessionStatus.PAUSED) &&
                        s.completedSeconds > 0L &&
                        s.presetHours == presetHours &&
                        s.plannedSeconds == plannedSeconds
                }
                .forEach { (index, _) ->
                    rows[index] = rows[index].copy(status = SessionStatus.ABANDONED, lastUpdatedAt = now)
                }
        }
    }

    private fun repository(dao: FakeBurnInSessionDao = FakeBurnInSessionDao()) =
        BurnInRepository(dao) to dao

    private fun session(
        planId: String,
        status: SessionStatus,
        completedSeconds: Long,
        startedAt: Long,
    ): BurnInSession {
        // 与生产一致的规模字段推导：planId → 方案 → (presetHours, plannedSeconds)
        val plan = when {
            planId.startsWith("classic_") -> BurnPlans.CLASSIC
            planId.startsWith("quick_") -> BurnPlans.quick(planId.removeSurrounding("quick_", "h").toInt())
            else -> BurnPlans.custom(planId.removeSurrounding("custom_", "h").toInt())
        }
        return BurnInSession(
            presetHours = presetHoursOf(plan),
            plannedSeconds = plan.totalSeconds,
            completedSeconds = completedSeconds,
            startedAt = startedAt,
            lastUpdatedAt = startedAt,
            status = status,
        )
    }

    // ------------------------------------------------------------------
    // latestResumableSession：状态/进度/方案过滤
    // ------------------------------------------------------------------

    @Test
    fun `无会话时无可续播会话`() = runBlocking {
        val (repo, _) = repository()
        assertNull(repo.latestResumableSession("quick_8h"))
    }

    @Test
    fun `RUNNING且已煲大于0的会话可续播`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.RUNNING, completedSeconds = 3_600L, startedAt = 100L))
        val found = repo.latestResumableSession("quick_8h")
        assertNotNull(found)
        assertEquals(3_600L, found!!.completedSeconds)
    }

    @Test
    fun `PAUSED且已煲大于0的会话可续播`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.PAUSED, completedSeconds = 720L, startedAt = 100L))
        assertEquals(720L, repo.latestResumableSession("quick_8h")!!.completedSeconds)
    }

    @Test
    fun `ABANDONED与COMPLETED不算可续播`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.ABANDONED, completedSeconds = 100L, startedAt = 100L))
        dao.insert(session("quick_8h", SessionStatus.COMPLETED, completedSeconds = 28_800L, startedAt = 200L))
        assertNull(repo.latestResumableSession("quick_8h"))
    }

    @Test
    fun `已完成秒数为0的会话不可续播`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.RUNNING, completedSeconds = 0L, startedAt = 100L))
        dao.insert(session("quick_8h", SessionStatus.PAUSED, completedSeconds = 0L, startedAt = 200L))
        assertNull(repo.latestResumableSession("quick_8h"))
    }

    @Test
    fun `同方案多条可续播时取最近开始的会话`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.PAUSED, completedSeconds = 100L, startedAt = 100L))
        dao.insert(session("quick_8h", SessionStatus.RUNNING, completedSeconds = 200L, startedAt = 300L))
        dao.insert(session("quick_8h", SessionStatus.PAUSED, completedSeconds = 300L, startedAt = 200L))
        assertEquals(200L, repo.latestResumableSession("quick_8h")!!.completedSeconds)
    }

    @Test
    fun `开始时间相同时取插入更晚的会话`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.PAUSED, completedSeconds = 111L, startedAt = 100L))
        dao.insert(session("quick_8h", SessionStatus.RUNNING, completedSeconds = 222L, startedAt = 100L))
        assertEquals(222L, repo.latestResumableSession("quick_8h")!!.completedSeconds)
    }

    @Test
    fun `不同方案之间不互相匹配`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_2h", SessionStatus.RUNNING, completedSeconds = 60L, startedAt = 100L))
        assertNull(repo.latestResumableSession("quick_8h"))
        // quick 与 custom 同小时数规模一致（均 7200s/2h），续播起点语义等价，可互相续播
        assertNotNull(repo.latestResumableSession("quick_2h"))
    }

    @Test
    fun `classic与custom方案均可正确匹配`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("classic_120h", SessionStatus.PAUSED, completedSeconds = 43_200L, startedAt = 100L))
        dao.insert(session("custom_36h", SessionStatus.RUNNING, completedSeconds = 1L, startedAt = 200L))
        assertEquals(43_200L, repo.latestResumableSession("classic_120h")!!.completedSeconds)
        assertEquals(1L, repo.latestResumableSession("custom_36h")!!.completedSeconds)
        assertNull(repo.latestResumableSession("classic_999h"))
    }

    @Test
    fun `非法planId返回null`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.RUNNING, completedSeconds = 60L, startedAt = 100L))
        assertNull(repo.latestResumableSession("bogus_plan"))
        assertNull(repo.latestResumableSession("quick_0h"))
        assertNull(repo.latestResumableSession("quick_abc"))
    }

    // ------------------------------------------------------------------
    // startSession：续播起点写入初始 completedSeconds
    // ------------------------------------------------------------------

    @Test
    fun `startSession默认从0起步与历史行为一致`() = runBlocking {
        val (repo, dao) = repository()
        val id = repo.startSession(BurnPlans.quick(8), startedAtMillis = 1_000L)
        val row = dao.rows.first { it.id == id }
        assertEquals(0L, row.completedSeconds)
        assertEquals(SessionStatus.RUNNING, row.status)
        assertEquals(8, row.presetHours)
        assertEquals(28_800L, row.plannedSeconds)
    }

    @Test
    fun `startSession把续播起点写入初始已完成秒数`() = runBlocking {
        val (repo, dao) = repository()
        val id = repo.startSession(BurnPlans.quick(8), startedAtMillis = 1_000L, initialCompletedSeconds = 3_600L)
        assertEquals(3_600L, dao.rows.first { it.id == id }.completedSeconds)
        // 新会话行自身即刻可续播（再次中断后可从最新进度继续）
        assertEquals(3_600L, repo.latestResumableSession("quick_8h")!!.completedSeconds)
    }

    @Test
    fun `startSession越界起点收敛到合法区间`() = runBlocking {
        val (repo, dao) = repository()
        repo.startSession(BurnPlans.quick(2), startedAtMillis = 1L, initialCompletedSeconds = -5L)
        repo.startSession(BurnPlans.quick(2), startedAtMillis = 2L, initialCompletedSeconds = 99_999L)
        assertEquals(listOf(0L, 7_200L), dao.rows.map { it.completedSeconds })
    }

    // ------------------------------------------------------------------
    // abandonResumableSessions：新会话前旧检查点被置 abandoned（检查点不无限累积）
    // ------------------------------------------------------------------

    @Test
    fun `新会话前同方案旧检查点被置为abandoned且不再可续播`() = runBlocking {
        val (repo, dao) = repository()
        // 旧「已暂停」检查点（用户上次中断留下的可续行）
        val oldId = dao.insert(session("quick_8h", SessionStatus.PAUSED, completedSeconds = 1_800L, startedAt = 100L))
        // 复刻 PlaybackController.startInternal 的调用顺序：先作废旧检查点，再插入新会话
        repo.abandonResumableSessions(BurnPlans.quick(8), nowMillis = 9_999L)
        val newId = repo.startSession(BurnPlans.quick(8), startedAtMillis = 10_000L, initialCompletedSeconds = 1_800L)
        // 旧检查点行仍在（历史可见）但已置 ABANDONED，不再进入续播查询
        assertEquals(SessionStatus.ABANDONED, dao.rows.first { it.id == oldId }.status)
        assertEquals(9_999L, dao.rows.first { it.id == oldId }.lastUpdatedAt)
        // 唯一可续播的是新会话
        assertEquals(newId, repo.latestResumableSession("quick_8h")!!.id)
    }

    @Test
    fun `作废检查点只影响同方案不波及其他方案`() = runBlocking {
        val (repo, dao) = repository()
        val quickId = dao.insert(session("quick_8h", SessionStatus.PAUSED, completedSeconds = 600L, startedAt = 100L))
        val classicId = dao.insert(session("classic_120h", SessionStatus.RUNNING, completedSeconds = 600L, startedAt = 200L))
        repo.abandonResumableSessions(BurnPlans.quick(8), nowMillis = 9_999L)
        assertEquals(SessionStatus.ABANDONED, dao.rows.first { it.id == quickId }.status)
        assertEquals(SessionStatus.RUNNING, dao.rows.first { it.id == classicId }.status)
    }

    @Test
    fun `已煲为0的检查点与已完结会话不受作废影响`() = runBlocking {
        val (repo, dao) = repository()
        // completedSeconds = 0 与 ABANDONED/COMPLETED 本就不是可续播行，作废操作不应改动它们
        val zeroId = dao.insert(session("quick_8h", SessionStatus.RUNNING, completedSeconds = 0L, startedAt = 100L))
        val doneId = dao.insert(session("quick_8h", SessionStatus.COMPLETED, completedSeconds = 28_800L, startedAt = 200L))
        repo.abandonResumableSessions(BurnPlans.quick(8), nowMillis = 9_999L)
        assertEquals(SessionStatus.RUNNING, dao.rows.first { it.id == zeroId }.status)
        assertEquals(SessionStatus.COMPLETED, dao.rows.first { it.id == doneId }.status)
    }

    // ------------------------------------------------------------------
    // sessionPage：分页口径与全量列表同序（startedAt DESC, id DESC）
    // ------------------------------------------------------------------

    @Test
    fun `分页按开始时间倒序返回`() = runBlocking {
        val (repo, dao) = repository()
        val first = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 1L, startedAt = 100L))
        val second = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 2L, startedAt = 300L))
        val third = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 3L, startedAt = 200L))
        // 最新开始在前：startedAt 300 -> 200 -> 100
        assertEquals(listOf(second, third, first), repo.sessionPage(limit = 10, offset = 0).map { it.id })
    }

    @Test
    fun `分页offset跳过已加载条目`() = runBlocking {
        val (repo, dao) = repository()
        val first = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 1L, startedAt = 100L))
        dao.insert(session("quick_2h", SessionStatus.COMPLETED, 2L, startedAt = 300L))
        val third = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 3L, startedAt = 200L))
        // 首屏 limit=2 取最新两条后，offset=1 应从次新一条继续
        assertEquals(listOf(third, first), repo.sessionPage(limit = 2, offset = 1).map { it.id })
    }

    @Test
    fun `分页limit截断且不足一页时全量返回`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_2h", SessionStatus.COMPLETED, 1L, startedAt = 100L))
        val second = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 2L, startedAt = 200L))
        dao.insert(session("quick_2h", SessionStatus.COMPLETED, 3L, startedAt = 300L))
        assertEquals(listOf(second), repo.sessionPage(limit = 1, offset = 1).map { it.id })
        // offset 越界返回空列表（到底的判定依据）
        assertTrue(repo.sessionPage(limit = 20, offset = 3).isEmpty())
    }

    @Test
    fun `开始时间相同时分页按插入先后倒序`() = runBlocking {
        val (repo, dao) = repository()
        val first = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 1L, startedAt = 100L))
        val second = dao.insert(session("quick_2h", SessionStatus.COMPLETED, 2L, startedAt = 100L))
        assertEquals(listOf(second, first), repo.sessionPage(limit = 10, offset = 0).map { it.id })
    }

    @Test
    fun `空表分页返回空列表`() = runBlocking {
        val (repo, _) = repository()
        assertTrue(repo.sessionPage(limit = 20, offset = 0).isEmpty())
    }

    // ------------------------------------------------------------------
    // sessionCount 与 clearAllSessions：清除重置（统计与列表同表，随之归零）
    // ------------------------------------------------------------------

    @Test
    fun `会话总数随插入与清除变化`() = runBlocking {
        val (repo, dao) = repository()
        assertEquals(0, repo.sessionCount.first())
        dao.insert(session("quick_2h", SessionStatus.COMPLETED, 100L, startedAt = 100L))
        dao.insert(session("quick_2h", SessionStatus.COMPLETED, 200L, startedAt = 200L))
        assertEquals(2, repo.sessionCount.first())
        repo.clearAllSessions()
        assertEquals(0, repo.sessionCount.first())
        assertTrue(repo.sessionPage(limit = 20, offset = 0).isEmpty())
    }

    @Test
    fun `清除后会话记录与累计统计一并归零`() = runBlocking {
        val (repo, dao) = repository()
        dao.insert(session("quick_8h", SessionStatus.RUNNING, completedSeconds = 3_600L, startedAt = 100L))
        dao.insert(session("classic_120h", SessionStatus.COMPLETED, completedSeconds = 43_200L, startedAt = 200L))
        assertEquals(46_800L, repo.totalCompletedSeconds.first())
        repo.clearAllSessions()
        assertEquals(0L, repo.totalCompletedSeconds.first())
        assertEquals(0, repo.sessionCount.first())
        // 可续播检查点同样被清空，续播入口不再出现
        assertNull(repo.latestResumableSession("quick_8h"))
    }
}
