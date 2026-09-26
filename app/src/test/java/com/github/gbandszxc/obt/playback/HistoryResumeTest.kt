package com.github.gbandszxc.obt.playback

import com.github.gbandszxc.obt.data.BurnInSession
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.domain.model.BurnPhase
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史记录续播的会话行重建纯函数测试：[BurnInViewModel.planForSession] 的各分支
 * （方案行 120h→经典 / 其余→自定义等比 / 自由合成音源→快捷 / 本地音乐与未知编号→不可重建、
 * 编排配置重组生效）与 [BurnInViewModel.sessionResumableFromHistory] 的显示口径。
 * 纯 JVM 断言，不依赖 Android 框架。
 */
class HistoryResumeTest {

    /** 构造一条会话行（缺省为已暂停、有进度的方案煲机会话）。 */
    private fun session(
        presetHours: Int,
        plannedSeconds: Long,
        soundSourceId: Int? = null,
        completedSeconds: Long = 3_600L,
        status: SessionStatus = SessionStatus.PAUSED,
    ) = BurnInSession(
        id = 1L,
        presetHours = presetHours,
        plannedSeconds = plannedSeconds,
        completedSeconds = completedSeconds,
        startedAt = 0L,
        lastUpdatedAt = 0L,
        status = status,
        soundSourceId = soundSourceId,
        soundLabel = null,
    )

    /** 缺省编排配置（与 BurnInUiState 默认值同口径）。 */
    private val defaultOrder = listOf(0, 1, 2, 3)
    private val defaultGains = emptyMap<Int, Double>()

    // ---- planForSession：方案煲机会话 ----

    @Test
    fun `经典规模方案行走经典方案重建`() {
        val plan = BurnInViewModel.planForSession(
            session = session(presetHours = 120, plannedSeconds = BurnPlans.CLASSIC_TOTAL_SECONDS),
            stageOrder = defaultOrder,
            stageGains = defaultGains,
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals(BurnPlans.CLASSIC.id, plan?.id)
        assertEquals(BurnPlans.CLASSIC_TOTAL_SECONDS, plan?.totalSeconds)
    }

    @Test
    fun `自定义方案行按预设小时数走等比方案且总时长一致`() {
        val plan = BurnInViewModel.planForSession(
            session = session(presetHours = 48, plannedSeconds = 48L * 3_600L),
            stageOrder = defaultOrder,
            stageGains = defaultGains,
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals("custom_48h", plan?.id)
        assertEquals(48L * 3_600L, plan?.totalSeconds)
    }

    @Test
    fun `方案行走当前编排配置重组`() {
        // 顺序重排：播放序列首位应为 stageId=3（轮换）
        val reordered = BurnInViewModel.planForSession(
            session = session(presetHours = 120, plannedSeconds = BurnPlans.CLASSIC_TOTAL_SECONDS),
            stageOrder = listOf(3, 2, 1, 0),
            stageGains = defaultGains,
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals(3, reordered?.phases?.first()?.stageId)

        // 响度覆盖：舒缓（stageId=0）比例应为覆盖值 0.5
        val overridden = BurnInViewModel.planForSession(
            session = session(presetHours = 48, plannedSeconds = 48L * 3_600L),
            stageOrder = defaultOrder,
            stageGains = mapOf(0 to 0.5),
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        val gentle: BurnPhase? = overridden?.phases?.firstOrNull { it.stageId == 0 }
        assertEquals(0.5, gentle?.volumeRatio ?: 0.0, 1e-9)
    }

    @Test
    fun `自定义方案行预设小时数非正时放弃重建`() {
        val plan = BurnInViewModel.planForSession(
            session = session(presetHours = 0, plannedSeconds = 0L),
            stageOrder = defaultOrder,
            stageGains = defaultGains,
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertNull(plan)
    }

    // ---- planForSession：自由煲机会话 ----

    @Test
    fun `自由合成音源行走快捷方案重建且时长与音源一致`() {
        val plan = BurnInViewModel.planForSession(
            session = session(presetHours = 8, plannedSeconds = 8L * 3_600L, soundSourceId = SoundSource.WHITE_NOISE.legacySoundId),
            stageOrder = defaultOrder,
            stageGains = defaultGains,
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals("quick_8h", plan?.id)
        assertEquals(8L * 3_600L, plan?.totalSeconds)
        assertEquals(SoundSource.WHITE_NOISE, plan?.phases?.first()?.soundSource)
        // 自由煲机维持播放器增益，与 startFree 组装的 quick 方案同构
        assertFalse(plan?.loudnessViaSystemVolume ?: true)
    }

    @Test
    fun `本地音乐自由煲机行不可重建`() {
        val plan = BurnInViewModel.planForSession(
            session = session(presetHours = 8, plannedSeconds = 8L * 3_600L, soundSourceId = SoundSource.LOCAL_TRACK.legacySoundId),
            stageOrder = defaultOrder,
            stageGains = defaultGains,
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertNull(plan)
    }

    @Test
    fun `未知音源编号自由煲机行不可重建`() {
        val plan = BurnInViewModel.planForSession(
            session = session(presetHours = 8, plannedSeconds = 8L * 3_600L, soundSourceId = 99),
            stageOrder = defaultOrder,
            stageGains = defaultGains,
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertNull(plan)
    }

    // ---- sessionResumableFromHistory ----

    @Test
    fun `续播显示口径与可重建口径一致`() {
        // 方案煲机（soundSourceId 为 null）总可续播
        assertTrue(
            BurnInViewModel.sessionResumableFromHistory(
                session(presetHours = 120, plannedSeconds = BurnPlans.CLASSIC_TOTAL_SECONDS),
            ),
        )
        // 内置合成音源可续播
        assertTrue(
            BurnInViewModel.sessionResumableFromHistory(
                session(presetHours = 8, plannedSeconds = 28_800L, soundSourceId = SoundSource.PINK_NOISE.legacySoundId),
            ),
        )
        // 本地音乐与未知编号不可续播
        assertFalse(
            BurnInViewModel.sessionResumableFromHistory(
                session(presetHours = 8, plannedSeconds = 28_800L, soundSourceId = SoundSource.LOCAL_TRACK.legacySoundId),
            ),
        )
        assertFalse(
            BurnInViewModel.sessionResumableFromHistory(
                session(presetHours = 8, plannedSeconds = 28_800L, soundSourceId = 99),
            ),
        )
    }
}
