package com.github.gbandszxc.obt.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方案煲机阶段编排配置的持久化序列化纯函数测试：
 * [parseStageOrder] / [parseStageGains] / [parseSteadyTrackIds]
 * 的默认回退、非法容错、边界值与 parse(format(x)) == x 往返一致。
 */
class SettingsRepositoryParseTest {

    // ---------- parseStageOrder ----------

    @Test
    fun `阶段顺序空值与空白回退默认顺序`() {
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder(null))
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder(""))
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("   "))
    }

    @Test
    fun `阶段顺序接受任意全排列`() {
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("0,1,2,3"))
        assertEquals(listOf(0, 1, 3, 2), parseStageOrder("0,1,3,2"))
        assertEquals(listOf(3, 2, 1, 0), parseStageOrder("3,2,1,0"))
        // 各项容忍首尾空白
        assertEquals(listOf(1, 0, 3, 2), parseStageOrder("1, 0, 3, 2"))
    }

    @Test
    fun `阶段顺序非全排列一律整体回退默认`() {
        // 重复（非排列）
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("0,0,1,2"))
        // 缺项
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("0,1,2"))
        // 含越界值 4
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("0,1,2,4"))
        // 含负数
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("0,1,2,-1"))
        // 含非数字
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("0,1,2,x"))
        // 尾随逗号产生空项，同样不构成全排列
        assertEquals(listOf(0, 1, 2, 3), parseStageOrder("0,1,2,"))
    }

    // ---------- parseStageGains ----------

    @Test
    fun `响度覆盖空值与空白返回空map`() {
        assertEquals(emptyMap<Int, Double>(), parseStageGains(null))
        assertEquals(emptyMap<Int, Double>(), parseStageGains(""))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("   "))
    }

    @Test
    fun `响度覆盖常规解析`() {
        assertEquals(mapOf(2 to 0.47, 0 to 0.15), parseStageGains("2:0.47,0:0.15"))
        assertEquals(mapOf(3 to 0.01), parseStageGains("3:0.01"))
    }

    @Test
    fun `响度覆盖非法项逐项跳过`() {
        // 全部非法 → 空 map：ratio 为 0 / 越界 / NaN / 非数字、stageId 非法、缺冒号
        assertEquals(emptyMap<Int, Double>(), parseStageGains("2:0"))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("2:1.5"))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("2:NaN"))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("2:abc"))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("x:0.5"))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("5:0.5"))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("2"))
        // 非法项夹在合法项之间：只跳过非法项，不中断其余项
        assertEquals(mapOf(2 to 0.47, 0 to 0.15), parseStageGains("2:0.47,bad,0:0.15"))
    }

    @Test
    fun `响度覆盖重复stageId取首个合法值`() {
        assertEquals(mapOf(1 to 0.5), parseStageGains("1:0.5,1:0.9"))
        // 首项非法被跳过后，取首个合法值
        assertEquals(mapOf(2 to 0.3), parseStageGains("2:1.5,2:0.3"))
    }

    @Test
    fun `响度覆盖比例边界`() {
        // ratio = 1.0 合法（整数字面量同样解析为 1.0）
        assertEquals(mapOf(2 to 1.0), parseStageGains("2:1.0"))
        assertEquals(mapOf(2 to 1.0), parseStageGains("2:1"))
        // ratio = 0 与略大于 1 均非法
        assertEquals(emptyMap<Int, Double>(), parseStageGains("2:0"))
        assertEquals(emptyMap<Int, Double>(), parseStageGains("2:1.0001"))
    }

    // ---------- parseSteadyTrackIds ----------

    @Test
    fun `稳定曲目空值与空白返回空列表`() {
        assertEquals(emptyList<Long>(), parseSteadyTrackIds(null))
        assertEquals(emptyList<Long>(), parseSteadyTrackIds(""))
        assertEquals(emptyList<Long>(), parseSteadyTrackIds("   "))
    }

    @Test
    fun `稳定曲目常规解析并容忍空白与空项`() {
        assertEquals(listOf(101L, 205L, 9L), parseSteadyTrackIds("101,205,9"))
        assertEquals(listOf(101L, 205L), parseSteadyTrackIds(" 101 , 205 "))
        // 空项跳过
        assertEquals(listOf(101L), parseSteadyTrackIds("101,,"))
    }

    @Test
    fun `稳定曲目非法项逐项跳过`() {
        // 非数字 / 负数 / 0（非正整数）均跳过
        assertEquals(listOf(101L, 205L), parseSteadyTrackIds("101,abc,-3,0,205"))
    }

    @Test
    fun `稳定曲目去重保序`() {
        assertEquals(listOf(7L, 3L, 9L), parseSteadyTrackIds("7,3,7,9,3"))
    }

    // ---------- 往返一致 ----------

    @Test
    fun `阶段顺序解析与序列化往返一致`() {
        val order = listOf(1, 3, 0, 2)
        assertEquals(order, parseStageOrder(formatStageOrder(order)))
        assertEquals(
            DEFAULT_PLAN_STAGE_ORDER,
            parseStageOrder(formatStageOrder(DEFAULT_PLAN_STAGE_ORDER)),
        )
    }

    @Test
    fun `响度覆盖解析与序列化往返一致`() {
        val gains = linkedMapOf(2 to 0.47, 0 to 0.15, 3 to 1.0)
        assertEquals(gains, parseStageGains(formatStageGains(gains)))
        assertEquals(emptyMap<Int, Double>(), parseStageGains(formatStageGains(emptyMap())))
    }

    @Test
    fun `稳定曲目解析与序列化往返一致`() {
        val ids = listOf(12L, 48L, 7L)
        assertEquals(ids, parseSteadyTrackIds(formatSteadyTrackIds(ids)))
        assertEquals(emptyList<Long>(), parseSteadyTrackIds(formatSteadyTrackIds(emptyList())))
    }
}
