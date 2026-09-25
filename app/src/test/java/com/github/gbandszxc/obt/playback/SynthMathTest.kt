package com.github.gbandszxc.obt.playback

import com.github.gbandszxc.obt.playback.SynthMath.PinkNoiseGenerator
import com.github.gbandszxc.obt.playback.SynthMath.RunningPeakNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [SynthMath] 纯合成数学测试：方波表、扫频序列、正弦表、粉噪滤波与混合值域，
 * 参数逐项对照原版实现的逆向结论。纯 JVM，不依赖 Android 框架。
 */
class SynthMathTest {

    // ---- 方波表 ----

    @Test
    fun `方波表长度为整秒44100样本且幅值仅为正负25000`() {
        val table = SynthMath.squareWaveTable(150)
        assertEquals(44_100, table.size)
        for (value in table) {
            assertTrue(value.toInt() == SynthMath.SQUARE_AMPLITUDE || value.toInt() == -SynthMath.SQUARE_AMPLITUDE)
        }
    }

    @Test
    fun `方波表逐样本符合原版公式_周期294负半周58`() {
        val table = SynthMath.squareWaveTable(150)
        // 原版方波生成公式：周期 44100/150 = 294，负半周 (int)(0.2 * 294.0) = 58
        for (i in table.indices) {
            assertEquals(if (i % 294 < 58) -25_000 else 25_000, table[i].toInt())
        }
    }

    @Test
    fun `方波表150Hz占空比为58比294`() {
        val table = SynthMath.squareWaveTable(150)
        val negativeCount = table.count { it < 0 }
        // 44100 = 150 × 294 恰好 150 个整周期，每周期负半周 58 样本
        assertEquals(150 * 58, negativeCount)
    }

    @Test
    fun `非整除频率仍按原版截断公式生成`() {
        // 195Hz：周期 44100/195 = 226（整除截断），负半周 (int)(0.2 * 226.15) = 45
        val table = SynthMath.squareWaveTable(195)
        for (i in table.indices) {
            assertEquals(if (i % 226 < 45) -25_000 else 25_000, table[i].toInt())
        }
    }

    // ---- 扫频频率序列 ----

    @Test
    fun `低频扫频序列为40点上行100至195下行200至105`() {
        val frequencies = SynthMath.lowSweepFrequencies()
        assertEquals(40, frequencies.size)
        assertEquals(100, frequencies.first())
        assertEquals(195, frequencies[19])
        assertEquals(200, frequencies[20])
        assertEquals(105, frequencies.last())
        // 上行严格递增、下行严格递减，全部为 5Hz 的倍数
        for (i in 0 until 19) assertTrue(frequencies[i] < frequencies[i + 1])
        for (i in 20 until 39) assertTrue(frequencies[i] > frequencies[i + 1])
        frequencies.forEach { assertEquals(0, it % 5) }
    }

    @Test
    fun `宽频扫频序列为74点覆盖100至10000`() {
        val frequencies = SynthMath.wideSweepFrequencies()
        assertEquals(74, frequencies.size)
        assertEquals(100, frequencies.first())
        // 上行 37 点：100→195(步进5) / 200→900(步进100) / 1000→9000(步进1000)
        assertEquals(195, frequencies[19])
        assertEquals(200, frequencies[20])
        assertEquals(900, frequencies[27])
        assertEquals(1000, frequencies[28])
        assertEquals(9000, frequencies[36])
        // 下行 37 点：10000→2000(步进1000) / 1000→200(步进100) / 195→105(步进5)，原版止于 105 不回写 100
        assertEquals(10_000, frequencies[37])
        assertEquals(2000, frequencies[45])
        assertEquals(1000, frequencies[46])
        assertEquals(900, frequencies[47])
        assertEquals(200, frequencies[54])
        assertEquals(195, frequencies[55])
        assertEquals(105, frequencies.last())
        // 上行严格递增、下行严格递减
        for (i in 0 until 36) assertTrue(frequencies[i] < frequencies[i + 1])
        for (i in 37 until 73) assertTrue(frequencies[i] > frequencies[i + 1])
    }

    @Test
    fun `扫频表每频点一张整秒方波表`() {
        val tables = SynthMath.sweepTables(SynthMath.lowSweepFrequencies())
        assertEquals(40, tables.size)
        tables.forEach { assertEquals(44_100, it.size) }
    }

    // ---- 正弦表 ----

    @Test
    fun `正弦表长度44100且首样本为过零点`() {
        val table = SynthMath.sineWaveTable()
        assertEquals(44_100, table.size)
        assertEquals(0, table[0].toInt())
    }

    @Test
    fun `正弦表幅值接近16bit满幅且对称`() {
        val table = SynthMath.sineWaveTable()
        val max = table.max()
        val min = table.min()
        assertTrue("正峰值应接近满幅：$max", max in 32_760..32_767)
        assertTrue("负峰值应接近满幅：$min", min in -32_767..-32_760)
    }

    @Test
    fun `正弦表300Hz每秒600次过零对应300个完整周期`() {
        val table = SynthMath.sineWaveTable()
        // 每个周期在 π 处发生一次相邻样本异号（另一次过零落在精确零样本上），300 周期 → 300 次
        val crossings = (0 until table.size - 1).count { table[it] * table[it + 1] < 0 }
        assertEquals(300, crossings)
        // 精确零样本数 = 周期数 300（147 样本/周期的整数倍位置）
        assertEquals(300, table.count { it.toInt() == 0 })
    }

    // ---- 粉噪滤波与生成器 ----

    @Test
    fun `粉噪输出有界且非静音`() {
        val generator = PinkNoiseGenerator()
        val out = ShortArray(44_100 * 3)
        generator.fill(out)
        for (value in out) {
            assertTrue(value in -32_767..32_767)
        }
        val nonzeroRatio = out.count { it.toInt() != 0 }.toDouble() / out.size
        assertTrue("粉噪不应静音：非零占比=$nonzeroRatio", nonzeroRatio > 0.99)
    }

    @Test
    fun `粉噪滤波状态跨缓冲连续`() {
        // 同一确定性白噪源、共享同一组状态量：整段一气滤波与分两段滤波逐样本一致
        // （注：运行峰值归一化按缓冲独立缩放，为忠实原版的逐缓冲行为，不属于滤波状态）
        val wholeState = SynthMath.PinkNoiseState()
        val wholeWhite = deterministicWhite(7L)
        val whole = FloatArray(4_096) { SynthMath.pinkFilterStep(wholeState, wholeWhite()) }

        val splitState = SynthMath.PinkNoiseState()
        val splitWhite = deterministicWhite(7L)
        val first = FloatArray(1_024) { SynthMath.pinkFilterStep(splitState, splitWhite()) }
        val second = FloatArray(3_072) { SynthMath.pinkFilterStep(splitState, splitWhite()) }

        for (i in first.indices) assertEquals(whole[i], first[i], 0.0f)
        for (i in second.indices) assertEquals(whole[first.size + i], second[i], 0.0f)
    }

    @Test
    fun `运行峰值归一化正负半轴分别收敛到满幅`() {
        val normalizer = RunningPeakNormalizer()
        // 首个正样本：×1.12202 后即成为运行峰值，缩放回 1.0
        assertEquals(1.0f, normalizer.normalize(floatArrayOf(1.0f))[0], 1e-6f)
        // 后续较小正值按同一峰值缩放
        assertEquals(0.5f, normalizer.normalize(floatArrayOf(0.5f))[0], 1e-6f)
        // 负半轴由独立的最小值归一化
        val negative = RunningPeakNormalizer()
        assertEquals(-1.0f, negative.normalize(floatArrayOf(-1.0f))[0], 1e-6f)
        assertEquals(-0.25f, negative.normalize(floatArrayOf(-0.25f))[0], 1e-6f)
    }

    // ---- 混合煲机 ----

    @Test
    fun `混合煲机输出值域有界且非静音`() {
        val generator = SynthMath.MixedNoiseGenerator()
        val out = ShortArray(44_100 * 2)
        generator.fill(out)
        for (value in out) {
            assertTrue(value in -32_767..32_767)
        }
        val nonzeroRatio = out.count { it.toInt() != 0 }.toDouble() / out.size
        assertTrue("混合音源不应静音：非零占比=$nonzeroRatio", nonzeroRatio > 0.99)
    }

    // ---- 音源目录 ----

    @Test
    fun `音源目录覆盖7个合成音源加本地音源占位且中文名正确`() {
        val expected = listOf(
            0 to "正弦波",
            1 to "粉红噪音",
            2 to "方波",
            3 to "白噪音",
            4 to "低频扫频",
            5 to "混合煲机",
            6 to "宽频扫频",
            7 to "本地音乐",
        )
        assertEquals(expected, com.github.gbandszxc.obt.domain.model.SoundSource.entries.map { it.legacySoundId to it.displayName })
    }

    @Test
    fun `UI音效目录不含本地音源占位项`() {
        // catalog 供下拉框等 UI 遍历使用：7 个合成音源，排除 LOCAL_TRACK
        assertEquals(7, com.github.gbandszxc.obt.domain.model.SoundSource.catalog.size)
        assertEquals(
            com.github.gbandszxc.obt.domain.model.SoundSource.entries.filter {
                it != com.github.gbandszxc.obt.domain.model.SoundSource.LOCAL_TRACK
            },
            com.github.gbandszxc.obt.domain.model.SoundSource.catalog,
        )
    }

    // ---- 工具 ----

    /** 确定性白噪源：注入生成器以保证跨缓冲连续性测试可复现。 */
    private fun deterministicWhite(seed: Long): () -> Float {
        val random = Random(seed)
        return { 2.0f * (random.nextFloat() - 0.5f) }
    }
}
