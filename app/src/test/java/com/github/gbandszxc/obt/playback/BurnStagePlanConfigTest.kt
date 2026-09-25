package com.github.gbandszxc.obt.playback

import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.domain.model.BurnPlans
import com.github.gbandszxc.obt.domain.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案阶段编排配置的状态派生测试：[BurnInUiState.effectiveSteadyTrackIds] /
 * [BurnInUiState.steadyMusicEffective] 派生口径，与方案组装纯函数
 * [BurnInUiState.buildStagePlan] 的各分支（默认等价/歌单注入/排序/响度覆盖/回退/组合叠加）。
 * 纯 JVM 断言，不依赖 Android 框架。
 */
class BurnStagePlanConfigTest {

    private fun track(id: Long) = LocalTrack(
        id = id,
        displayName = "曲目$id.flac",
        fileName = "uuid-$id.audio",
        addedAt = 0L,
    )

    private val tracks = listOf(track(1L), track(2L), track(3L))

    // ---- effectiveSteadyTrackIds / steadyMusicEffective ----

    @Test
    fun `开关关闭时生效歌单恒为空`() {
        val state = BurnInUiState(
            tracks = tracks,
            steadyMusicEnabled = false,
            steadyTrackIds = listOf(1L, 2L),
        )
        assertEquals(emptyList<Long>(), state.effectiveSteadyTrackIds)
        assertFalse(state.steadyMusicEffective)
    }

    @Test
    fun `开关开启时生效歌单为与曲库的交集且保序去重`() {
        // 3,1 为存在曲目且保持勾选顺序；重复 id 去重；99 已失效剔除
        val state = BurnInUiState(
            tracks = tracks,
            steadyMusicEnabled = true,
            steadyTrackIds = listOf(3L, 99L, 1L, 1L),
        )
        assertEquals(listOf(3L, 1L), state.effectiveSteadyTrackIds)
        assertTrue(state.steadyMusicEffective)
    }

    @Test
    fun `开关开启但歌单为空或全部失效时回退粉噪`() {
        val emptyPlaylist = BurnInUiState(tracks = tracks, steadyMusicEnabled = true)
        assertEquals(emptyList<Long>(), emptyPlaylist.effectiveSteadyTrackIds)
        assertFalse(emptyPlaylist.steadyMusicEffective)

        val allMissing = BurnInUiState(
            tracks = tracks,
            steadyMusicEnabled = true,
            steadyTrackIds = listOf(8L, 9L),
        )
        assertEquals(emptyList<Long>(), allMissing.effectiveSteadyTrackIds)
        assertFalse(allMissing.steadyMusicEffective)
    }

    // ---- buildStagePlan ----

    @Test
    fun `默认配置组装与现行为完全等价`() {
        val classic = BurnInUiState.buildStagePlan(
            hours = null,
            classic = true,
            stageOrder = listOf(0, 1, 2, 3),
            stageGains = emptyMap(),
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals(BurnPlans.CLASSIC, classic)

        val custom = BurnInUiState.buildStagePlan(
            hours = 48,
            classic = false,
            stageOrder = listOf(0, 1, 2, 3),
            stageGains = emptyMap(),
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals(BurnPlans.custom(48), custom)
    }

    @Test
    fun `启用歌单时稳定阶段注入本地音乐`() {
        val plan = BurnInUiState.buildStagePlan(
            hours = null,
            classic = true,
            stageOrder = listOf(0, 1, 2, 3),
            stageGains = emptyMap(),
            steadyEnabled = true,
            steadyTrackIds = listOf(5L, 6L),
        )
        // 方案 id 不随注入变化，续播匹配口径不变
        assertEquals(BurnPlans.CLASSIC.id, plan.id)
        val steady = plan.phases.first { it.stageId == 2 }
        assertEquals(SoundSource.LOCAL_TRACK, steady.soundSource)
        assertEquals(listOf(5L, 6L), steady.localTrackIds)
    }

    @Test
    fun `开关开启但歌单为空时回退粉噪恒定`() {
        val plan = BurnInUiState.buildStagePlan(
            hours = null,
            classic = true,
            stageOrder = listOf(0, 1, 2, 3),
            stageGains = emptyMap(),
            steadyEnabled = true,
            steadyTrackIds = emptyList(),
        )
        assertEquals(BurnPlans.CLASSIC, plan)
    }

    @Test
    fun `阶段顺序覆盖按身份重排并重编序号`() {
        val plan = BurnInUiState.buildStagePlan(
            hours = null,
            classic = true,
            stageOrder = listOf(2, 1, 0, 3),
            stageGains = emptyMap(),
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals(listOf(2, 1, 0, 3), plan.phases.map { it.stageId })
        assertEquals(listOf(0, 1, 2, 3), plan.phases.map { it.index })
    }

    @Test
    fun `响度覆盖按阶段身份应用且未覆盖阶段保持默认`() {
        val plan = BurnInUiState.buildStagePlan(
            hours = null,
            classic = true,
            stageOrder = listOf(0, 1, 2, 3),
            stageGains = mapOf(2 to 0.47, 0 to 0.15),
            steadyEnabled = false,
            steadyTrackIds = emptyList(),
        )
        assertEquals(0.15, plan.phases.first { it.stageId == 0 }.volumeRatio, 1e-9)
        assertEquals(0.47, plan.phases.first { it.stageId == 2 }.volumeRatio, 1e-9)
        assertEquals(1.0 / 3.0, plan.phases.first { it.stageId == 1 }.volumeRatio, 1e-9)
    }

    @Test
    fun `顺序响度与歌单组合叠加`() {
        val plan = BurnInUiState.buildStagePlan(
            hours = 48,
            classic = false,
            stageOrder = listOf(3, 2, 1, 0),
            stageGains = mapOf(0 to 0.2),
            steadyEnabled = true,
            steadyTrackIds = listOf(7L),
        )
        // 播放顺序按覆盖后的排列；稳定阶段（身份 2）注入音乐；舒缓（身份 0）覆盖响度
        assertEquals(listOf(3, 2, 1, 0), plan.phases.map { it.stageId })
        val steady = plan.phases[1]
        assertEquals(SoundSource.LOCAL_TRACK, steady.soundSource)
        assertEquals(listOf(7L), steady.localTrackIds)
        assertEquals(0.2, plan.phases[3].volumeRatio, 1e-9)
        // 自定义方案 id 不随编排变化，续播匹配口径不变
        assertEquals("custom_48h", plan.id)
    }
}
