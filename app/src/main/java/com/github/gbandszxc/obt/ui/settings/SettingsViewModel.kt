package com.github.gbandszxc.obt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.data.AppLanguage
import com.github.gbandszxc.obt.data.DEFAULT_PALETTE_ID
import com.github.gbandszxc.obt.data.SettingsRepository
import com.github.gbandszxc.obt.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 设置页聚合 UI 状态：通用（语言）+ 外观三项（主题）+ 播放两项（常亮/不息屏）的原子快照。
 * 各字段初值与 [SettingsRepository] 的缺省值一一对应，DataStore 首帧发射后即被真实值覆盖。
 */
data class SettingsUiState(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteId: String = DEFAULT_PALETTE_ID,
    val dynamicColor: Boolean = true,
    val keepScreenOn: Boolean = false,
    val dimKeepAlive: Boolean = false,
)

/**
 * 设置 Tab 的 ViewModel：聚合 [SettingsRepository] 各字段为单一 [StateFlow]，并提供挂起更新。
 *
 * 煲机页顶栏的屏幕常亮开关与设置页共用同一实例（Activity 级 ViewModelStore），
 * 两处 UI 读同一数据源，不出现状态分叉。
 */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    /** 设置聚合快照。Eagerly 与 [com.github.gbandszxc.obt.ui.history.HistoryViewModel] 同款约定。 */
    val settings: StateFlow<SettingsUiState> = combine(
        repository.themeSettings,
        repository.keepScreenOn,
        repository.dimKeepAlive,
        repository.language,
    ) { theme, keepScreenOn, dimKeepAlive, language ->
        SettingsUiState(
            language = language,
            themeMode = theme.themeMode,
            paletteId = theme.paletteId,
            dynamicColor = theme.dynamicColor,
            keepScreenOn = keepScreenOn,
            dimKeepAlive = dimKeepAlive,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    /** 保存主题模式。 */
    suspend fun setThemeMode(mode: ThemeMode) = repository.setThemeMode(mode)

    /** 保存主题配色（调色盘 id）。 */
    suspend fun setPaletteId(id: String) = repository.setPaletteId(id)

    /** 保存动态取色开关。 */
    suspend fun setDynamicColor(enabled: Boolean) = repository.setDynamicColor(enabled)

    /** 保存屏幕常亮开关（煲机页顶栏与设置页共用字段）。 */
    suspend fun setKeepScreenOn(enabled: Boolean) = repository.setKeepScreenOn(enabled)

    /** 保存不息屏模式开关。 */
    suspend fun setDimKeepAlive(enabled: Boolean) = repository.setDimKeepAlive(enabled)

    /** 保存应用界面语言（进程内当前值由调用方同步更新并重建界面，见 [AppLocale]）。 */
    suspend fun setLanguage(language: AppLanguage) = repository.setLanguage(language)

    companion object {
        /** 手动注入工厂（与 [com.github.gbandszxc.obt.playback.BurnInViewModel] 同一套约定）。 */
        fun factory(application: BurnInApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(application.appContainer.settingsRepository)
            }
        }
    }
}
