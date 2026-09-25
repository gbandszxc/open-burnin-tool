package com.github.gbandszxc.obt.playback

/**
 * 阶段响度比例 → 系统媒体音量档位（AudioManager STREAM_MUSIC）映射：
 *
 * `target = round(ratio × maxIndex)`（四舍五入，.5 向上），收敛到 0..[maxIndex]；
 * 比例大于 0 时档位至少为 1（比例再小也不完全静音，保留可闻下限）；
 * [maxIndex] <= 0（极端机型无可用档位）恒为 0。
 *
 * 纯逻辑（只依赖入参，不触碰 AudioManager），供 PlaybackController 在系统音量模式下
 * 换算档位，JVM 单测覆盖边界（见 SystemVolumeLoudnessTest）。
 *
 * @param ratio 响度比例，正常输入已由 BurnPhase 校验在 [0.0, 1.0]；
 *   越界输入防御性收敛到该区间、NaN 按 0 处理，不抛异常。
 * @param maxIndex 系统媒体流最大档位（AudioManager.getStreamMaxVolume 结果，非负）。
 * @return 目标档位，落在 0..[maxIndex]；比例 > 0 且 [maxIndex] > 0 时保证 >= 1。
 */
internal fun streamVolumeIndexFor(ratio: Double, maxIndex: Int): Int {
    if (maxIndex <= 0) return 0
    val clamped = ratio.coerceIn(0.0, 1.0)
    if (clamped.isNaN() || clamped <= 0.0) return 0
    // Math.round 为四舍五入（.5 向上）：与「比例 × 满档取整」的直觉映射一致
    return Math.round(clamped * maxIndex).toInt().coerceIn(1, maxIndex)
}
