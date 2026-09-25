package com.github.gbandszxc.obt.domain.model

/**
 * 煲机方案中的一个阶段。
 *
 * 标准四阶段为「舒缓 12h 白噪 → 适应 12h 粉噪 → 稳定 72h 粉噪 → 轮换 24h 白噪↔粉噪轮换」，
 * 每阶段固定响度比例（[volumeRatio]）：方案煲机经系统媒体音量表达、自由煲机为播放器级
 * 增益，由方案级标记 [BurnPlan.loudnessViaSystemVolume] 区分。
 *
 * 阶段有双重定位：[index] 表示播放顺序位置（排序后重编），[stageId] 表示阶段身份
 * （稳定标识，随阶段走）；排序、音量覆盖、单曲注入等编排操作一律按 [stageId] 定位。
 *
 * @property index 阶段序号，从 0 开始，随 [BurnPlan.phases] 顺序递增；
 *   仅表示播放顺序位置，经 [BurnPlan.withStageOrder] 重排后会被重编。
 * @property stageId 阶段身份，标准四阶段为 0=舒缓 / 1=适应 / 2=稳定 / 3=轮换。
 *   构造默认 -1 表示「历史默认」占位（保证既有构造点零改动能编译），但 [BurnPlan] 层
 *   会拒绝默认值——同方案内 stageId 必须唯一且 >= 0，编排入口须显式传入；
 *   快捷单阶段方案固定取 0。stageId 一经确定不随播放顺序改变。
 * @property name 阶段内部标识（gentle/adapt/steady/alternate 等，仅日志/测试用）；
 *   用户可见的阶段名由展示层按阶段序号经资源解析（见 ui/PlanDisplay.kt）。
 * @property durationSeconds 阶段时长（秒）。
 * @property soundSource 基准音源。
 * @property volumeRatio 音量比例，取值 [0.0, 1.0]，如标准四阶段为 1/5、1/3、7/15、3/5；
 *   编排期可按身份整体覆盖（见 [BurnPlan.withStageGains]）。
 * @property alternateWith 轮换音源；为 null 表示本阶段不轮换。
 *   注意：与本阶段携带 [localTrackIds] 歌单互斥。
 * @property alternateEverySeconds 轮换周期（秒）。标准方案的轮换阶段为 1800（30 分钟），
 *   按「阶段内已播秒数 / 周期」的奇偶切换基准音源与 [alternateWith]。
 * @property localTrackIds 本地音乐有序歌单（Room local_tracks 表音轨 id 列表，见
 *   com.github.gbandszxc.obt.data.LocalTrack）；**非空表示本阶段循环播放该有序歌单
 *   （本地音乐阶段的唯一表达，单曲即单元素列表），音量走 [volumeRatio]，
 *   [alternateWith]/[alternateEverySeconds] 不参与本阶段，
 *   且 [soundSource] 必须为 [SoundSource.LOCAL_TRACK]**。默认空列表表示合成音源阶段。
 */
data class BurnPhase(
    val index: Int,
    val name: String,
    val durationSeconds: Long,
    val soundSource: SoundSource,
    val volumeRatio: Double,
    val alternateWith: SoundSource? = null,
    val alternateEverySeconds: Long? = null,
    val stageId: Int = -1,
    val localTrackIds: List<Long> = emptyList(),
) {
    init {
        require(durationSeconds > 0) { "阶段时长必须为正数：$durationSeconds" }
        require(volumeRatio in 0.0..1.0) { "音量比例越界：$volumeRatio" }
        require((alternateWith == null) == (alternateEverySeconds == null)) {
            "轮换音源与轮换周期必须同时配置或同时为空"
        }
        if (alternateEverySeconds != null) {
            require(alternateEverySeconds > 0) { "轮换周期必须为正数：$alternateEverySeconds" }
        }
        // 有序歌单约束：id 全为正数且不重复；与轮换互斥；音源必须为本地音乐占位枚举
        if (localTrackIds.isNotEmpty()) {
            require(localTrackIds.all { it > 0L }) { "歌单音轨 id 必须全为正数：$localTrackIds" }
            require(localTrackIds.toSet().size == localTrackIds.size) {
                "歌单音轨 id 不得重复：$localTrackIds"
            }
            require(alternateWith == null) { "同一阶段不可既配置轮换又配置本地音乐歌单" }
            require(soundSource == SoundSource.LOCAL_TRACK) {
                "携带歌单的阶段音源必须为 LOCAL_TRACK：$soundSource"
            }
        }
    }

    /** 阶段内已播 [elapsedInPhaseSeconds] 秒时应当使用的音源。 */
    fun soundSourceAt(elapsedInPhaseSeconds: Long): SoundSource {
        val period = alternateEverySeconds
        val alternate = alternateWith
        if (period == null || alternate == null) return soundSource
        val switchParity = (elapsedInPhaseSeconds.coerceAtLeast(0L) / period) % 2L
        return if (switchParity == 1L) alternate else soundSource
    }
}

/**
 * 有序歌单的「下一可用曲目」定位：从 [from] 的下一位起去重保序循环查找
 * （绕整圈恰好覆盖全部候选，含单元素歌单回环到自身），跳过 [unavailable] 中
 * 已判定不可用的音轨 id；全部音轨不可用返回 null。
 *
 * 纯逻辑（只依赖入参与 [List.equals]，不查库不做 IO），供播放链路在曲目播完/
 * 起播失败时计算推进目标，JVM 单测覆盖（见 NextPlayableIndexTest）。
 *
 * @param from 当前播放序号（调用方保证落在歌单下标范围内；越界按模运算回环，不抛错）。
 */
internal fun nextPlayableIndex(playlist: List<Long>, from: Int, unavailable: Set<Long>): Int? {
    if (playlist.isEmpty()) return null
    for (step in 1..playlist.size) {
        val candidate = Math.floorMod(from + step, playlist.size)
        if (playlist[candidate] !in unavailable) return candidate
    }
    return null
}

/**
 * 煲机方案：阶段按顺序衔接，总时长为各阶段之和。
 *
 * 原版默认方案总时长 432000 秒（120 小时）。
 *
 * 方案层校验阶段身份：同方案内各阶段 [BurnPhase.stageId] 必须唯一且 >= 0
 * （拒绝构造默认的 -1 占位），保证按身份编排（排序/覆盖/注入）定位无歧义。
 */
data class BurnPlan(
    val id: String,

    /** 方案内部标识（仅日志/测试用）；用户可见的方案名由展示层按 [id] 经资源解析。 */
    val name: String,
    val phases: List<BurnPhase>,

    /**
     * 响度表达方式标记：true = 方案煲机，各阶段响度经系统媒体音量
     * （AudioManager STREAM_MUSIC）表达，播放器增益恒 1.0；false = 现状，响度为
     * 播放器级增益（MediaPlayer.setVolume / AudioTrack.setVolume）。
     * classic()/custom() 工厂产出 true，quick() 保持 false；[withStageOrder]/
     * [withStageGains] 等编排派生经 data class copy 随对象保留该标记，无需特判。
     */
    val loudnessViaSystemVolume: Boolean = false,
) {
    init {
        require(phases.isNotEmpty()) { "煲机方案至少需要一个阶段" }
        require(phases.withIndex().all { (position, phase) -> phase.index == position }) {
            "阶段序号必须与列表位置一致（从 0 连续递增）"
        }
        require(phases.all { it.stageId >= 0 }) {
            "阶段身份 stageId 必须显式指定且非负（构造默认 -1 不可直接成案）"
        }
        require(phases.map { it.stageId }.toSet().size == phases.size) {
            "同方案内阶段身份 stageId 必须唯一：${phases.map { it.stageId }}"
        }
    }

    /** 总时长（秒）。 */
    val totalSeconds: Long = phases.sumOf { it.durationSeconds }

    /**
     * 按阶段身份重排播放顺序：[order] 为本方案 [BurnPhase.stageId] 的全排列，
     * 重排后重编 [BurnPhase.index]（0..n-1 连续）；排序只改播放顺序，
     * 各阶段时长/音源/音量等属性随阶段身份原样保留。
     *
     * @throws IllegalArgumentException [order] 不是本方案 stageId 的全排列
     *   （缺项/重复/包含未知身份/长度不符）。
     */
    fun withStageOrder(order: List<Int>): BurnPlan {
        val currentIds = phases.map { it.stageId }
        require(order.size == currentIds.size && order.toSet() == currentIds.toSet()) {
            "order 必须是方案内 stageId 的全排列：order=$order，方案内身份=$currentIds"
        }
        val byStage = phases.associateBy { it.stageId }
        val reindexed = order.mapIndexed { position, stageId ->
            byStage.getValue(stageId).copy(index = position)
        }
        return copy(phases = reindexed)
    }

    /**
     * 按阶段身份覆盖音量比例：[gains] 键为 [BurnPhase.stageId]、值为新比例，
     * 必须落在 (0, 1]（开区间不含 0）；map 中缺省的身份保持原值，
     * 本方案不存在的 stageId 宽容忽略（不抛错）。
     * 比例范围对 [gains] 全部取值校验（含被忽略身份对应的取值）。
     *
     * @throws IllegalArgumentException 任一 ratio 落在 (0, 1] 之外。
     */
    fun withStageGains(gains: Map<Int, Double>): BurnPlan {
        gains.forEach { (stageId, ratio) ->
            require(ratio > 0.0 && ratio <= 1.0) { "音量比例越界（须在 (0, 1]）：stageId=$stageId, ratio=$ratio" }
        }
        val patched = phases.map { phase ->
            gains[phase.stageId]?.let { ratio -> phase.copy(volumeRatio = ratio) } ?: phase
        }
        return copy(phases = patched)
    }
}
