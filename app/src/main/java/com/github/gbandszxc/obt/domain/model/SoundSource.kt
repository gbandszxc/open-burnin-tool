package com.github.gbandszxc.obt.domain.model

import androidx.annotation.StringRes
import com.github.gbandszxc.obt.R

/**
 * 煲机音源目录：7 类纯合成音源 + 本地音乐音源占位项。
 *
 * 每个枚举项携带三份事实：
 * - [legacySoundId]：原 App 内部音源编号；
 * - [nameRes]：展示名资源 id（UI/通知按当前应用语言解析，中英文见 values-zh/values）；
 * - [defaultGainRatio]：默认播放增益（播放器级增益，不劫持系统媒体音量）。
 *   取值为「用户自由选择音源且无阶段音量」场景的推荐值，参照原版四阶段音量梯度
 *   （1/5、1/3、7/15、3/5）：持续谱音源（正弦/方波/扫频/白噪）取温和的 0.2；
 *   粉噪与混合经峰值归一化后能量密度更高，取 1/3。
 *
 * UI 音效目录请使用 [catalog]（不含 [LOCAL_TRACK]），它只是本地音源阶段
 * 在方案模型中的占位枚举，不对应任何内置资产。
 */
enum class SoundSource(
    val legacySoundId: Int,
    @StringRes val nameRes: Int,
    val defaultGainRatio: Double,
) {

    /**
     * 正弦波 300Hz 持续音，原 App 音源编号 0。
     * 原版实现为 8bit 遗留缺陷代码（等效约 300Hz），本版重写为标准 16bit 正弦。
     */
    SINE_WAVE(0, R.string.sound_sine_wave, 0.2),

    /** 粉红噪音（Paul Kellet 滤波 + 运行峰值归一化），原 App 音源编号 1。 */
    PINK_NOISE(1, R.string.sound_pink_noise, 1.0 / 3.0),

    /** 方波 150Hz（整秒表循环，幅值 ±25000），原 App 音源编号 2。 */
    SQUARE_WAVE(2, R.string.sound_square_wave, 0.2),

    /** 白噪音（均匀满幅、无滤波，连续生成），原 App 音源编号 3。 */
    WHITE_NOISE(3, R.string.sound_white_noise, 0.2),

    /** 低频扫频 100–200Hz 阶梯方波（每频点 1 秒，40 秒无缝循环），原 App 音源编号 4。 */
    LOW_FREQ_SWEEP(4, R.string.sound_low_freq_sweep, 0.2),

    /**
     * 混合煲机：白噪 + 粉噪时域各 50% 混合。
     * 原 App 音源编号 5 仅有 UI 占位无实现，本版补全为 (白噪 + 粉噪) / 2 后走同一峰值归一化。
     */
    MIXED_BURN(5, R.string.sound_mixed_burn, 1.0 / 3.0),

    /** 宽频扫频 100Hz–10kHz 阶梯方波（每频点 1 秒，74 秒无缝循环），原 App 音源编号 6。 */
    WIDE_FREQ_SWEEP(6, R.string.sound_wide_freq_sweep, 0.2),

    /**
     * 本地音乐音源占位项（原 App 音源编号 7 的「音乐煲机」已随内置音频资产移除）。
     * 不对应任何内置资产，也不进 UI 音效目录（见 [catalog]）；仅当方案阶段携带
     * 本地歌单（[BurnPhase.localTrackIds] 非空）时
     * 作为该阶段的音源枚举，实际播放 MediaPlayer 加载用户私有目录的音轨文件，UI 显示曲目名。
     *
     * [defaultGainRatio] 取原版稳定阶段的 7/15，仅作为本地音源阶段的占位音量
     * （无阶段音量场景的兜底，实际方案音量由 [BurnPhase.volumeRatio] 决定）。
     */
    LOCAL_TRACK(7, R.string.sound_local_track, 7.0 / 15.0);

    companion object {

        /**
         * UI 音效目录：用户可选的内置合成音源，即 [entries] 中排除 [LOCAL_TRACK]。
         * 下拉框等音效遍历一律使用本列表，避免把本地音源占位项当内置音效展示。
         */
        val catalog: List<SoundSource> = entries.filter { it != LOCAL_TRACK }

        /**
         * 按原版音源编号反查音源枚举（历史页会话行回显自由煲机所用音效用）：
         * null 或未知编号一律返回 null，调用方据此回退为不展示音效。
         */
        fun fromLegacySoundId(id: Int?): SoundSource? =
            id?.let { legacyId -> entries.firstOrNull { it.legacySoundId == legacyId } }
    }
}
