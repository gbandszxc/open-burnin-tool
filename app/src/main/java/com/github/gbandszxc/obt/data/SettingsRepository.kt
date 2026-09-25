package com.github.gbandszxc.obt.data

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 默认调色盘 id：对应 ui/theme/Color.kt 调色盘目录中的「青瓷绿」（首位）。 */
const val DEFAULT_PALETTE_ID = "celadon"

/**
 * 主题模式：
 * - [SYSTEM] 跟随系统深浅色；
 * - [LIGHT] / [DARK] 应用内强制浅色 / 深色。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        /** 从持久化字符串解析，空值/非法值（含历史脏数据）一律回退 [SYSTEM]，向前兼容枚举演进。 */
        fun fromRaw(raw: String?): ThemeMode = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * 主题设置快照：仓库内多个设置字段的一次原子读取结果。
 * 首帧同步初值（[SettingsRepository.snapshotOnce]）与 flow 收集共用同一结构，避免字段错位。
 */
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteId: String = DEFAULT_PALETTE_ID,
    val dynamicColor: Boolean = true,
)

/** DataStore 单例委托：进程内同名文件只允许存在一个 DataStore 实例（官方约束）。 */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 方案煲机阶段编排配置的持久化序列化层：三对 parse/format 顶层纯函数（无 Android 依赖，JVM 单测覆盖）。
 *
 * - 阶段顺序：`"0,1,2,3"` 逗号分隔，必须是 0..3 的全排列，否则整体回退默认顺序；
 * - 四阶段响度覆盖：`"2:0.47,0:0.15"` 即 `stageId:ratio` 逗号分隔，非法项逐项跳过，
 *   结果只含用户显式覆盖的阶段；
 * - 稳定阶段曲目：`"12,48"` 逗号分隔正长整数曲目 id，非法项逐项跳过、去重保序。
 *
 * parse 与 format 互为单一来源，往返一致：parse(format(x)) == x。
 */

/** 方案煲机四阶段缺省播放顺序：0 舒缓 → 1 适应 → 2 稳定 → 3 轮换。 */
internal val DEFAULT_PLAN_STAGE_ORDER = listOf(0, 1, 2, 3)

/** 合法阶段 id 集合（0..3），用于顺序的全排列校验与响度覆盖的值域校验。 */
private val PLAN_STAGE_ID_SET = DEFAULT_PLAN_STAGE_ORDER.toSet()

/**
 * 解析方案煲机阶段播放顺序：必须是 0..3 的全排列（数量恰为 4、无重复、值域合法）才接受；
 * 空值/空白/缺项/重复/越界/非数字一律整体回退 [DEFAULT_PLAN_STAGE_ORDER]。
 */
internal fun parseStageOrder(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return DEFAULT_PLAN_STAGE_ORDER
    val stages = raw.split(',').map { it.trim().toIntOrNull() }
    if (null in stages) return DEFAULT_PLAN_STAGE_ORDER
    val ids = stages.filterNotNull()
    return if (ids.size == PLAN_STAGE_ID_SET.size && ids.toSet() == PLAN_STAGE_ID_SET) {
        ids
    } else {
        DEFAULT_PLAN_STAGE_ORDER
    }
}

/**
 * 解析四阶段响度覆盖：`stageId:ratio` 逗号分隔（如 `"2:0.47,0:0.15"`）。
 *
 * - ratio 须落在 (0, 1]（0、越界、NaN/Infinity 视为非法）；
 * - stageId 须在 0..3 内；
 * - 非法项逐项跳过、不中断其余项，重复 stageId 取首个合法值；
 * - 空值/空白/全部非法返回空 map（= 各阶段全部沿用方案默认比例 1/5、1/3、7/15、3/5）。
 */
internal fun parseStageGains(raw: String?): Map<Int, Double> {
    if (raw.isNullOrBlank()) return emptyMap()
    val gains = linkedMapOf<Int, Double>()
    for (token in raw.split(',')) {
        val parts = token.split(':', limit = 2)
        if (parts.size != 2) continue
        val stageId = parts[0].trim().toIntOrNull() ?: continue
        if (stageId !in PLAN_STAGE_ID_SET) continue
        val ratio = parts[1].trim().toDoubleOrNull() ?: continue
        // toDoubleOrNull 会放行 "NaN"/"Infinity" 字面量，需一并排除
        if (!ratio.isFinite() || ratio <= 0.0 || ratio > 1.0) continue
        if (stageId !in gains) gains[stageId] = ratio
    }
    return gains
}

/**
 * 解析稳定阶段替换曲目 id 列表：逗号分隔正长整数。
 * 非法项（非数字/非正数）逐项跳过，去重保序；空值/空白返回空列表。
 */
internal fun parseSteadyTrackIds(raw: String?): List<Long> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .filter { it > 0L }
        .distinct()
}

/** 序列化阶段播放顺序（[parseStageOrder] 的逆）。 */
internal fun formatStageOrder(order: List<Int>): String = order.joinToString(",")

/** 序列化四阶段响度覆盖（[parseStageGains] 的逆），格式 `stageId:ratio` 逗号分隔。 */
internal fun formatStageGains(gains: Map<Int, Double>): String =
    gains.entries.joinToString(",") { (stageId, ratio) -> "$stageId:$ratio" }

/** 序列化稳定阶段替换曲目 id 有序列表（[parseSteadyTrackIds] 的逆）。 */
internal fun formatSteadyTrackIds(trackIds: List<Long>): String = trackIds.joinToString(",")

/**
 * 应用设置仓库（Application 级单例，经 [AppContainer] 注入）。
 *
 * - 持久化用 Preferences DataStore，单一 "settings" 文件；
 * - 对外 flow 均为进程内唯一实例（构造时创建一次），可直接被上层 combine/收集，
 *   不会每次访问新建 flow；
 * - 扩展约定：后续新增设置字段（如播放类偏好）按「Keys 增加键 + 单字段稳定 flow + 挂起 setter +
 *   必要时并入聚合快照」的模式添加，不另开文件；
 * - 方案煲机阶段编排配置（阶段顺序/响度覆盖/稳定阶段音乐替换）以可解析字符串持久化，
 *   解析与序列化收敛到文件内顶层纯函数，便于 JVM 单测。
 */
class SettingsRepository(private val appContext: Context) {

    /** 所有持久化键集中定义，命名带字段语义前缀，便于跨字段检索与排障。 */
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PALETTE_ID = stringPreferencesKey("palette_id")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DIM_KEEP_ALIVE = booleanPreferencesKey("dim_keep_alive")
        val LANGUAGE = stringPreferencesKey("language")
        val PLAN_STAGE_ORDER = stringPreferencesKey("plan_stage_order")
        val PLAN_STAGE_GAINS = stringPreferencesKey("plan_stage_gains")
        val PLAN_STEADY_MUSIC_ENABLED = booleanPreferencesKey("plan_steady_music_enabled")
        val PLAN_STEADY_TRACK_IDS = stringPreferencesKey("plan_steady_track_ids")
    }

    /** 底层偏好流：读文件抛 IOException（如首次损坏）时按空偏好处理，不让整条流中断；其余异常照抛。 */
    private val preferences: Flow<Preferences> = appContext.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    /** 主题模式，缺省跟随系统。 */
    val themeMode: Flow<ThemeMode> = preferences
        .map { ThemeMode.fromRaw(it[Keys.THEME_MODE]) }
        .distinctUntilChanged()

    /** 调色盘 id，缺省 [DEFAULT_PALETTE_ID]（青瓷绿）。 */
    val paletteId: Flow<String> = preferences
        .map { it[Keys.PALETTE_ID] ?: DEFAULT_PALETTE_ID }
        .distinctUntilChanged()

    /** 是否跟随壁纸动态取色（仅 Android 12+ 生效），缺省开启，与改造前视觉一致。 */
    val dynamicColor: Flow<Boolean> = preferences
        .map { it[Keys.DYNAMIC_COLOR] ?: true }
        .distinctUntilChanged()

    /** 屏幕常亮（煲机页顶栏与设置页读写同一字段），缺省关闭。 */
    val keepScreenOn: Flow<Boolean> = preferences
        .map { it[Keys.KEEP_SCREEN_ON] ?: false }
        .distinctUntilChanged()

    /** 不息屏模式（煲机播放中保持亮屏、无操作后降至最低亮度），缺省关闭。 */
    val dimKeepAlive: Flow<Boolean> = preferences
        .map { it[Keys.DIM_KEEP_ALIVE] ?: false }
        .distinctUntilChanged()

    /** 应用界面语言，缺省跟随系统自动检测。持久化与 [com.github.gbandszxc.obt.locale.AppLocale] 配合使用。 */
    val language: Flow<AppLanguage> = preferences
        .map { AppLanguage.fromRaw(it[Keys.LANGUAGE]) }
        .distinctUntilChanged()

    /**
     * 方案煲机阶段播放顺序（0 舒缓 → 1 适应 → 2 稳定 → 3 轮换）。
     * 缺省/存储脏数据整体回退 [DEFAULT_PLAN_STAGE_ORDER]。
     */
    val planStageOrder: Flow<List<Int>> = preferences
        .map { parseStageOrder(it[Keys.PLAN_STAGE_ORDER]) }
        .distinctUntilChanged()

    /**
     * 四阶段响度覆盖：stageId → 音量比例（(0, 1]，如 0.2 表示 20%）。
     * 只含用户显式覆盖的阶段；未出现的阶段由调用方沿用方案默认比例（1/5、1/3、7/15、3/5）。
     */
    val planStageGains: Flow<Map<Int, Double>> = preferences
        .map { parseStageGains(it[Keys.PLAN_STAGE_GAINS]) }
        .distinctUntilChanged()

    /**
     * 稳定阶段（stageId = 2）音乐替换开关，缺省 false（粉噪恒定）。
     * 仅当本开关为 true 且 [planSteadyTrackIds] 非空时，音乐替换才视为生效配置。
     */
    val planSteadyMusicEnabled: Flow<Boolean> = preferences
        .map { it[Keys.PLAN_STEADY_MUSIC_ENABLED] ?: false }
        .distinctUntilChanged()

    /** 稳定阶段替换曲目的有序 id 列表，缺省空列表。 */
    val planSteadyTrackIds: Flow<List<Long>> = preferences
        .map { parseSteadyTrackIds(it[Keys.PLAN_STEADY_TRACK_IDS]) }
        .distinctUntilChanged()

    /** 主题设置聚合流：三字段原子快照，供 Activity 首帧与持续收集使用。 */
    val themeSettings: Flow<ThemeSettings> = combine(
        themeMode,
        paletteId,
        dynamicColor,
    ) { mode, palette, dynamic ->
        ThemeSettings(themeMode = mode, paletteId = palette, dynamicColor = dynamic)
    }

    /**
     * 挂起读取一次当前主题设置快照。
     * 供 MainActivity 首帧以 runBlocking 同步取初值（本地文件读取毫秒级），
     * 保证首帧即为用户保存的主题，不出现浅/深闪烁。
     */
    suspend fun snapshotOnce(): ThemeSettings = themeSettings.first()

    /** 挂起读取一次应用语言：供 Application 启动时同步初始化 [com.github.gbandszxc.obt.locale.AppLocale]。 */
    suspend fun languageOnce(): AppLanguage = language.first()

    /** 保存主题模式。 */
    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    /** 保存调色盘 id。 */
    suspend fun setPaletteId(id: String) {
        appContext.settingsDataStore.edit { it[Keys.PALETTE_ID] = id }
    }

    /** 保存动态取色开关。 */
    suspend fun setDynamicColor(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    /** 保存屏幕常亮开关。 */
    suspend fun setKeepScreenOn(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    /** 保存不息屏模式开关。 */
    suspend fun setDimKeepAlive(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.DIM_KEEP_ALIVE] = enabled }
    }

    /** 保存应用界面语言。 */
    suspend fun setLanguage(language: AppLanguage) {
        appContext.settingsDataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    /** 保存方案煲机阶段播放顺序（须为 0..3 全排列，由调用方保证）。 */
    suspend fun setPlanStageOrder(order: List<Int>) {
        appContext.settingsDataStore.edit { it[Keys.PLAN_STAGE_ORDER] = formatStageOrder(order) }
    }

    /** 保存四阶段响度覆盖（只写覆盖阶段；未提供的阶段沿用方案默认比例）。 */
    suspend fun setPlanStageGains(gains: Map<Int, Double>) {
        appContext.settingsDataStore.edit { it[Keys.PLAN_STAGE_GAINS] = formatStageGains(gains) }
    }

    /** 保存稳定阶段音乐替换开关。 */
    suspend fun setPlanSteadyMusicEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.PLAN_STEADY_MUSIC_ENABLED] = enabled }
    }

    /** 保存稳定阶段替换曲目 id 有序列表。 */
    suspend fun setPlanSteadyTrackIds(trackIds: List<Long>) {
        appContext.settingsDataStore.edit { it[Keys.PLAN_STEADY_TRACK_IDS] = formatSteadyTrackIds(trackIds) }
    }
}
