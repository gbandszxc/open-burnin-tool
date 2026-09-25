package com.github.gbandszxc.obt.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.github.gbandszxc.obt.domain.model.SoundSource
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 煲机音源波形合成纯数学：表生成、滤波与归一化全部不依赖 Android 类型，
 * 可直接在 JVM 单元测试中运行。
 *
 * 采样参数逐项对应原 App 的 AudioTrack 配置（44100Hz / CHANNEL_OUT_MONO /
 * ENCODING_PCM_16BIT）与原版各音源生成公式。
 */
internal object SynthMath {

    /** 原版 AudioTrack 采样率。 */
    const val SAMPLE_RATE = 44_100

    /** 方波幅值：原版恒为 ±25000。 */
    const val SQUARE_AMPLITUDE = 25_000

    /** 16bit 满幅换算系数：原版 (short)(32767.0f * x)。 */
    const val FULL_SCALE = 32_767.0f

    /** 正弦波频率：300Hz 持续音（原版 0 号音源为 8bit 缺陷代码，等效约 300Hz，本版标准重写）。 */
    const val SINE_FREQUENCY_HZ = 300

    /** 方波频率：150Hz（原版音源 2 的生成频率）。 */
    const val SQUARE_FREQUENCY_HZ = 150

    /** 单张波形表长度：整秒 44100 样本（原版每频点 1 秒）。 */
    private const val TABLE_SAMPLES = SAMPLE_RATE

    /** 方波负半周占空比：原版 (int)(0.2 * (44100.0 / freq))。 */
    private const val SQUARE_NEGATIVE_DUTY = 0.2

    /**
     * 方波表：1 秒 44100 样本。周期 period = SAMPLE_RATE / frequencyHz（整除），
     * 负半周宽 = (0.2 * 44100.0 / frequencyHz) 截断取整，
     * 样本按 `i % period < 负半周宽 ? -25000 : +25000` 生成（与原版逐字对应）。
     */
    fun squareWaveTable(frequencyHz: Int): ShortArray {
        val period = SAMPLE_RATE / frequencyHz
        val negativeWidth = (SQUARE_NEGATIVE_DUTY * (SAMPLE_RATE.toDouble() / frequencyHz)).toInt()
        val negative = (-SQUARE_AMPLITUDE).toShort()
        val positive = SQUARE_AMPLITUDE.toShort()
        return ShortArray(TABLE_SAMPLES) { i ->
            if (i % period < negativeWidth) negative else positive
        }
    }

    /**
     * 正弦表：1 秒 44100 样本的标准 16bit 正弦 `(short)(32767 * sin(2π·f·t/SAMPLE_RATE))`。
     * 本版对原版 0 号音源（8bit 遗留缺陷）的重写；300Hz 在 44100Hz 下整周期（147 点/周），
     * 整秒表首尾相位连续，可无缝循环。
     */
    fun sineWaveTable(frequencyHz: Int = SINE_FREQUENCY_HZ): ShortArray = ShortArray(TABLE_SAMPLES) { i ->
        (FULL_SCALE * sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE.toDouble())).toInt().toShort()
    }

    /**
     * 低频扫频频率序列（与原版一致）：
     * 上行 100,105,…,195（步进 5Hz），下行 200,195,…,105（步进 5Hz），共 40 点 = 40 秒/循环。
     */
    fun lowSweepFrequencies(): List<Int> = buildList {
        var f = 100
        while (f < 200) {
            add(f)
            f += 5
        }
        f = 200
        while (f > 100) {
            add(f)
            f -= 5
        }
    }

    /**
     * 宽频扫频频率序列（与原版一致）：
     * 上行 100→195 步进 5Hz、200→900 步进 100Hz、1000→9000 步进 1000Hz（37 点）；
     * 下行 10000→2000 步进 1000Hz、1000→200 步进 100Hz、195→105 步进 5Hz（37 点，
     * 原版下行循环止于 105，不回写 100），共 74 点 = 74 秒/循环。
     */
    fun wideSweepFrequencies(): List<Int> = buildList {
        var step = 5
        var f = 100
        while (f < 10_000) {
            add(f)
            step = when {
                f < 200 -> step
                f < 1000 -> 100
                else -> 1000
            }
            f += step
        }
        f = 10_000
        while (f > 100) {
            add(f)
            step = when {
                f <= 200 -> 5
                f <= 1000 -> 100
                else -> step
            }
            f -= step
        }
    }

    /** 扫频总表：每个频点一张整秒方波表，按序列顺序循环播放。 */
    fun sweepTables(frequencies: List<Int>): List<ShortArray> = frequencies.map { squareWaveTable(it) }

    /** 均匀白噪浮点源：原版 `2.0f * (Math.random() - 0.5f)`（均匀分布 [-1, 1)）。 */
    internal fun defaultWhiteSource(): () -> Float = { 2.0f * (Random.Default.nextFloat() - 0.5f) }

    /** 合成样本流契约：向 [out] 填满下一段 16bit PCM 样本。 */
    fun interface SampleStream {
        fun fill(out: ShortArray)
    }

    /**
     * Paul Kellet 粉噪滤波的 7 个状态量（b0..b6），与原版实现一致：
     * 跨缓冲持续保留，是粉噪「记忆」的载体，不可在缓冲边界重置。
     */
    class PinkNoiseState {
        var b0 = 0.0f
        var b1 = 0.0f
        var b2 = 0.0f
        var b3 = 0.0f
        var b4 = 0.0f
        var b5 = 0.0f
        var b6 = 0.0f
    }

    /**
     * 单步 Kellet 滤波（musicdsp 经典系数，与原版逐字对应）：
     * 输入白噪样本 w，更新 [state] 并输出粉噪浮点样本。
     */
    fun pinkFilterStep(state: PinkNoiseState, white: Float): Float {
        state.b0 = 0.99886f * state.b0 + white * 0.0555179f
        state.b1 = 0.99332f * state.b1 + white * 0.0750759f
        state.b2 = 0.96900f * state.b2 + white * 0.153852f
        state.b3 = 0.86650f * state.b3 + white * 0.310486f
        state.b4 = 0.55000f * state.b4 + white * 0.5329522f
        state.b5 = -0.7616f * state.b5 - white * 0.016898f
        val output = state.b0 + state.b1 + state.b2 + state.b3 + state.b4 + state.b5 +
            state.b6 + white * 0.5362f
        state.b6 = white * 0.115926f
        return output
    }

    /**
     * 运行峰值归一化（与原版一致）：
     * 样本先乘 10^0.05 ≈ 1.12202；再用跨调用累计的运行峰值
     * max（初始 -2.0）与 min（初始 +2.0）分别缩放正负半轴——
     * 正样本 ×(1/max)，负样本 ×(-1/min)——使输出收敛于 [-1, 1]。
     */
    class RunningPeakNormalizer {

        private var runningMax = -2.0f
        private var runningMin = 2.0f

        /** 就地归一化并返回同一数组。峰值状态跨调用累计（忠实原版跨缓冲维护）。 */
        fun normalize(samples: FloatArray): FloatArray {
            val boosted = 1.12202f
            for (i in samples.indices) {
                val value = samples[i] * boosted
                if (value < runningMin) runningMin = value
                if (value > runningMax) runningMax = value
                samples[i] = value
            }
            val positiveScale = 1.0f / runningMax
            val negativeScale = -1.0f / runningMin
            for (i in samples.indices) {
                samples[i] = if (samples[i] > 0.0f) samples[i] * positiveScale else samples[i] * negativeScale
            }
            return samples
        }
    }

    /** 浮点样本转 16bit PCM：`(short)(32767.0f * x)`（原版换算）。 */
    fun toShortSample(value: Float): Short = (FULL_SCALE * value).toInt().toShort()

    /**
     * 白噪样本流：均匀满幅、无滤波，连续生成
     * （原版预生成 20 段随机换段播放的连续化重写，消除周期感的方式等价且更简单）。
     */
    class WhiteNoiseGenerator(whiteSource: () -> Float = defaultWhiteSource()) : SampleStream {

        private val white = whiteSource

        /** 填充 [out]：每个样本 `(short)(32767 * 2 * (rand - 0.5))`（原版换算）。 */
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                out[i] = toShortSample(white())
            }
        }
    }

    /**
     * 粉噪样本流：白噪源经 Kellet 滤波 + 运行峰值归一化。
     * 滤波状态与峰值状态均跨缓冲保留（忠实原版成员字段生命周期）。
     */
    class PinkNoiseGenerator(whiteSource: () -> Float = defaultWhiteSource()) : SampleStream {

        private val white = whiteSource
        private val state = PinkNoiseState()
        private val normalizer = RunningPeakNormalizer()

        /** 填充 [out]：滤波 → ×1.12202 → 运行峰值归一化 → (short)(32767·x)。 */
        override fun fill(out: ShortArray) {
            val floats = FloatArray(out.size) { white() }
            for (i in floats.indices) {
                floats[i] = pinkFilterStep(state, floats[i])
            }
            normalizer.normalize(floats)
            for (i in floats.indices) {
                out[i] = toShortSample(floats[i])
            }
        }
    }

    /**
     * 混合煲机样本流：白噪 + 粉噪时域各 50% 混合后走同一峰值归一化。
     * 原版 5 号音源仅有 UI 占位无实现，此为本版补全实现。
     */
    class MixedNoiseGenerator(whiteSource: () -> Float = defaultWhiteSource()) : SampleStream {

        private val white = whiteSource
        private val state = PinkNoiseState()
        private val normalizer = RunningPeakNormalizer()

        /** 填充 [out]：(白噪 + 粉噪) / 2 → ×1.12202 → 运行峰值归一化 → 16bit。 */
        override fun fill(out: ShortArray) {
            val floats = FloatArray(out.size)
            for (i in floats.indices) {
                val w = white()
                floats[i] = (w + pinkFilterStep(state, w)) / 2.0f
            }
            normalizer.normalize(floats)
            for (i in floats.indices) {
                out[i] = toShortSample(floats[i])
            }
        }
    }
}

/**
 * 统一煲机音源播放器抽象：屏蔽 AudioTrack（合成音源）与 MediaPlayer（本地音乐文件）
 * 差异，供 [PlaybackController] 以同一套暂停/继续/增益/释放路径驱动。
 */
interface BurnSoundPlayer {

    /** 起播（首次创建后调用）。 */
    fun start()

    /** 暂停：保留现场（缓冲/进度），可 [resume]。 */
    fun pause()

    /** 从暂停恢复。 */
    fun resume()

    /** 设置播放增益比例 [ratio] ∈ [0, 1]（播放器级增益，不触碰系统音量）。 */
    fun setGain(ratio: Double)

    /** 释放底层资源；释放后不可再使用。 */
    fun release()
}

/**
 * 合成音源播放器：全部内置音源（正弦/方波/扫频/白噪/粉噪/混合）
 * 经 AudioTrack 流式写入（MODE_STREAM 阻塞写，44100Hz/单声道/16bit，对应原版配置）。
 *
 * 线程模型：后台写入线程循环「生成一块 → 阻塞写入」；暂停仅 [AudioTrack.pause]
 * （播放头停走、缓冲保留，写线程因缓冲写满自然阻塞在 write 上，波形相位不丢失）；
 * 恢复 [AudioTrack.play] 后写入继续。释放时 pause + flush 丢弃缓冲解除阻塞，
 * join 写线程后 release，保证无线程泄漏。
 *
 * 增益走 [AudioTrack.setVolume]（float 轨道增益，不劫持系统媒体音量）。
 *
 * @param source 合成音源种类（不可为 [SoundSource.LOCAL_TRACK]）。
 */
internal class AudioTrackBurnSoundPlayer(source: SoundSource) : BurnSoundPlayer {

    init {
        require(source != SoundSource.LOCAL_TRACK) { "本地音乐音源请使用 MediaPlayer 文件播放器（见 PlaybackController.createLocalFilePlayer）" }
    }

    private val track: AudioTrack = buildTrack()

    /** 样本流：表类音源整循环表循环写，噪声类连续生成。 */
    private val stream: SynthMath.SampleStream = createStream(source)

    /** 线程名用的音源标识（构造参数不在成员方法作用域内，此处固化）。 */
    private val sourceTag: String = source.name

    private var writerThread: Thread? = null

    @Volatile
    private var running = false

    override fun start() {
        if (running) return
        running = true
        val thread = Thread({ writeLoop() }, "burnin-synth-$sourceTag")
        writerThread = thread
        track.play()
        thread.start()
    }

    override fun pause() {
        runCatching { track.pause() }
    }

    override fun resume() {
        runCatching { track.play() }
    }

    override fun setGain(ratio: Double) {
        runCatching { track.setVolume(ratio.coerceIn(0.0, 1.0).toFloat()) }
    }

    override fun release() {
        running = false
        runCatching { track.pause() }
        // 丢弃未播缓冲，解除写线程在 write() 上的阻塞
        runCatching { track.flush() }
        writerThread?.join(JOIN_TIMEOUT_MILLIS)
        writerThread = null
        runCatching { track.release() }
    }

    /** 写入循环：生成一块 → 阻塞写入，直至停止或轨道失效。 */
    private fun writeLoop() {
        val chunk = ShortArray(CHUNK_SAMPLES)
        while (running) {
            stream.fill(chunk)
            val written = track.write(chunk, 0, chunk.size)
            if (written < 0) {
                Log.w(TAG, "AudioTrack 写入错误（code=$written），写入线程退出")
                break
            }
        }
    }

    /** 按原版参数建轨：44100Hz / CHANNEL_OUT_MONO / PCM_16BIT / MODE_STREAM。 */
    private fun buildTrack(): AudioTrack {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            SynthMath.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1)
        val bufferBytes = maxOf(minBufferBytes * 2, CHUNK_SAMPLES * 2 * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SynthMath.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    /** 合成样本流：表类音源整循环表循环写，噪声类连续生成。 */
    private fun createStream(source: SoundSource): SynthMath.SampleStream = when (source) {
        SoundSource.SINE_WAVE -> LoopingTableStream(listOf(SynthMath.sineWaveTable()))
        SoundSource.SQUARE_WAVE -> LoopingTableStream(listOf(SynthMath.squareWaveTable(SynthMath.SQUARE_FREQUENCY_HZ)))
        SoundSource.LOW_FREQ_SWEEP -> LoopingTableStream(SynthMath.sweepTables(SynthMath.lowSweepFrequencies()))
        SoundSource.WIDE_FREQ_SWEEP -> LoopingTableStream(SynthMath.sweepTables(SynthMath.wideSweepFrequencies()))
        SoundSource.WHITE_NOISE -> SynthMath.WhiteNoiseGenerator()
        SoundSource.PINK_NOISE -> SynthMath.PinkNoiseGenerator()
        SoundSource.MIXED_BURN -> SynthMath.MixedNoiseGenerator()
        SoundSource.LOCAL_TRACK -> throw IllegalArgumentException("本地音乐音源请使用 MediaPlayer 文件播放器")
    }

    /**
     * 表循环流：多张整秒表按序拼接成一个大循环（方波单表、扫频 40/74 表），
     * 跨表边界无缝衔接，循环末尾回卷到首表。
     */
    private class LoopingTableStream(private val tables: List<ShortArray>) : SynthMath.SampleStream {

        init {
            require(tables.isNotEmpty()) { "循环表不能为空" }
        }

        private var tableIndex = 0
        private var offsetInTable = 0

        override fun fill(out: ShortArray) {
            var filled = 0
            while (filled < out.size) {
                val table = tables[tableIndex]
                val copy = min(table.size - offsetInTable, out.size - filled)
                System.arraycopy(table, offsetInTable, out, filled, copy)
                filled += copy
                offsetInTable += copy
                if (offsetInTable >= table.size) {
                    offsetInTable = 0
                    tableIndex = (tableIndex + 1) % tables.size
                }
            }
        }
    }

    private companion object {
        const val TAG = "SynthPlayer"

        /** 写入块大小：4410 样本 = 100ms，兼顾延迟与切换粒度。 */
        const val CHUNK_SAMPLES = 4_410

        /** 释放时等待写线程退出的超时（毫秒）。 */
        const val JOIN_TIMEOUT_MILLIS = 1_000L
    }
}

/** 音源播放器工厂：内置合成音源走 AudioTrack（本地音乐文件由 PlaybackController 单独构建 MediaPlayer）。 */
internal object BurnSoundPlayers {

    /** 按音源创建对应播放器（不自动起播）。 */
    fun create(source: SoundSource): BurnSoundPlayer = when (source) {
        SoundSource.LOCAL_TRACK -> throw IllegalArgumentException("本地音乐音源请使用 MediaPlayer 文件播放器（见 PlaybackController.createLocalFilePlayer）")
        else -> AudioTrackBurnSoundPlayer(source)
    }
}
