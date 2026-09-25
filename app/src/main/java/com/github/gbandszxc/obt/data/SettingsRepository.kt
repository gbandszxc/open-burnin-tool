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
 * 应用设置仓库（Application 级单例，经 [AppContainer] 注入）。
 *
 * - 持久化用 Preferences DataStore，单一 "settings" 文件；
 * - 对外 flow 均为进程内唯一实例（构造时创建一次），可直接被上层 combine/收集，
 *   不会每次访问新建 flow；
 * - 扩展约定：后续新增设置字段（如播放类偏好）按「Keys 增加键 + 单字段稳定 flow + 挂起 setter +
 *   必要时并入聚合快照」的模式添加，不另开文件。
 */
class SettingsRepository(private val appContext: Context) {

    /** 所有持久化键集中定义，命名带字段语义前缀，便于跨字段检索与排障。 */
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PALETTE_ID = stringPreferencesKey("palette_id")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DIM_KEEP_ALIVE = booleanPreferencesKey("dim_keep_alive")
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
}
