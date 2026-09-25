package com.github.gbandszxc.obt.update

import com.github.gbandszxc.obt.data.SettingsRepository
import com.github.gbandszxc.obt.data.UpdateSnoozeMode
import kotlinx.coroutines.flow.first

/**
 * 更新提示「稍后」策略：本次为进程内状态，7 天/下个版本持久化在 [SettingsRepository]。
 */
class UpdateSnoozeStore(private val repository: SettingsRepository) {

    /**
     * 进程内的「本次」策略：只跳过 [onceVersion] 这个版本的下一次自动提示，读取即消费。
     * 用实例私有字段而非伴生静态，便于单测隔离；容器中本就是进程级单例，语义等价。
     */
    private var onceVersion: String? = null

    /** 只在当前进程内跳过下一次自动提示（不落库）。 */
    fun snoozeOnce(versionName: String) {
        onceVersion = normalizeVersion(versionName)
    }

    /** 从 [nowMs] 起 7 天内不再自动提示。 */
    suspend fun snoozeForSevenDays(versionName: String, nowMs: Long = System.currentTimeMillis()) {
        repository.setUpdateSnooze(
            mode = UpdateSnoozeMode.SEVEN_DAYS,
            version = normalizeVersion(versionName),
            untilMs = nowMs + SEVEN_DAYS_MS,
        )
    }

    /** 只跳过 [versionName] 这个版本。 */
    suspend fun snoozeUntilNextVersion(versionName: String) {
        repository.setUpdateSnooze(
            mode = UpdateSnoozeMode.UNTIL_NEXT_VERSION,
            version = normalizeVersion(versionName),
            untilMs = null,
        )
    }

    /** 自动提示是否应被跳过。 */
    suspend fun shouldSkipAutomaticPrompt(
        versionName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalized = normalizeVersion(versionName)
        if (onceVersion == normalized) {
            // 读取即消费：「本次」只对紧邻的下一次自动提示生效
            onceVersion = null
            return true
        }

        val state = repository.updateSnooze.first()
        return when (state.mode) {
            UpdateSnoozeMode.SEVEN_DAYS -> {
                val untilMs = state.untilMs
                if (untilMs != null && nowMs < untilMs) {
                    true
                } else {
                    // 到期或缺到期时间视为失效：顺手清理，避免脏数据长期驻留
                    clear()
                    false
                }
            }
            UpdateSnoozeMode.UNTIL_NEXT_VERSION -> state.version == normalized
            null -> false
        }
    }

    /** 清除全部「稍后」策略（含进程内的本次）。 */
    suspend fun clear() {
        onceVersion = null
        repository.clearUpdateSnooze()
    }

    companion object {
        const val SEVEN_DAYS_MS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}
