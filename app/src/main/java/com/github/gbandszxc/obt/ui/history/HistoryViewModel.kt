package com.github.gbandszxc.obt.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.data.BurnInRepository
import com.github.gbandszxc.obt.data.BurnInSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 记录 Tab 的 ViewModel：分页加载的会话列表 + 聚合统计 + 清除重置。
 *
 * 分页设计（首屏 [PAGE_SIZE] 条，滚动到列表末尾由 UI 调 [loadNextPage] 追加）：
 * - 状态为「已加载列表 [sessions] + 是否到底 [endReached] + 加载中 [isLoadingMore]」，
 *   UI 各取所需（BurnInApp 只消费已加载列表与累计时长，辅助状态由记录页自取同一实例）；
 * - 会话表任何增删改都会让 Room 重发 [BurnInRepository.sessionCount]（COUNT 聚合流，
 *   只重查一个数字），借它在数据变化后按「当前已加载数」重载列表，而不观察全量列表流——
 *   那会让分页失去意义；StateFlow 同值自动去重，UI 不会因进度更新而闪烁；
 * - 累计时长与会话次数仍是聚合 Flow；统计与列表同表，清除后自然归零。
 *
 * 仍用 [SharingStarted.Eagerly] 而非 WhileSubscribed：煲机 Tab 也依赖累计时长与
 * 「会话完成」事件（BurnInApp 的 CompletionFeedback 观察已加载列表），切走记录 Tab
 * 后上游不能停。
 */
class HistoryViewModel(private val repository: BurnInRepository) : ViewModel() {

    /** 串行化「重载」与「追加」两类列表写入，避免交错产生重复行/缺行。 */
    private val loadMutex = Mutex()
    private val _sessions = MutableStateFlow<List<BurnInSession>>(emptyList())

    /** 已加载的会话（分页累积），按开始时间倒序。 */
    val sessions: StateFlow<List<BurnInSession>> = _sessions.asStateFlow()

    private val _endReached = MutableStateFlow(false)

    /** 是否已加载全部会话（到底；为 true 时列表尾显示结束提示，不再触发加载）。 */
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)

    /** 是否正在追加下一页（列表尾显示加载中）。 */
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _sessionCount = MutableStateFlow(0)

    /** 会话总数（聚合值：小结「会话次数」与到底提示「共 N 条」共用）。 */
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()

    /** 累计煲机秒数（所有会话 completedSeconds 之和，聚合 Flow 不变）。 */
    val totalCompletedSeconds: StateFlow<Long> = repository.totalCompletedSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    init {
        // COUNT 流兼作表变更信号：Room 在表任何写操作后重查重发（含数值不变的进度更新），
        // 每次发射都重载「已加载数」条；展示层由各 StateFlow 同值去重。
        viewModelScope.launch {
            repository.sessionCount.collect { count ->
                _sessionCount.value = count
                reloadLoaded()
            }
        }
    }

    /**
     * 按当前已加载数重载列表（offset 0）：新会话出现在顶部、进度/状态原地刷新；
     * 行被删除（如清除重置）后列表随之收缩。结束按「已加载 >= 总数」判定是否到底。
     */
    private suspend fun reloadLoaded() {
        loadMutex.withLock {
            val target = _sessions.value.size.coerceAtLeast(PAGE_SIZE)
            val loaded = repository.sessionPage(limit = target, offset = 0)
            _sessions.value = loaded
            _endReached.value = loaded.size >= _sessionCount.value
        }
    }

    /**
     * 追加下一页（滚动接近列表末尾时由 UI 触发）：已在加载或已到底时空操作，
     * 可安全重复触发。返回页不足 [PAGE_SIZE] 即判到底。
     */
    fun loadNextPage() {
        if (_isLoadingMore.value || _endReached.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                loadMutex.withLock {
                    if (!_endReached.value) {
                        val offset = _sessions.value.size
                        val page = repository.sessionPage(limit = PAGE_SIZE, offset = offset)
                        if (page.isNotEmpty()) {
                            _sessions.value = _sessions.value + page
                        }
                        _endReached.value = page.size < PAGE_SIZE ||
                            _sessions.value.size >= _sessionCount.value
                    }
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /** 清除重置：删除全部会话（累计统计同表，随之自动归零）。 */
    fun clearAll() {
        viewModelScope.launch { repository.clearAllSessions() }
    }

    companion object {
        /** 分页大小：首屏与每次滚动追加的行数。 */
        private const val PAGE_SIZE = 20

        /** 手动注入工厂（与 [com.github.gbandszxc.obt.playback.BurnInViewModel] 同一套约定）。 */
        fun factory(application: BurnInApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(application.appContainer.burnInRepository)
            }
        }
    }
}
