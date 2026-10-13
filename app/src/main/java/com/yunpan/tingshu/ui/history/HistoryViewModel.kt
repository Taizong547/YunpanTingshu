package com.yunpan.tingshu.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.data.AccountStore
import com.yunpan.tingshu.data.db.HistoryDao
import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.data.db.ListenDao
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.ui.common.Fmt
import com.yunpan.tingshu.ui.common.HistoryMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryEntity> = emptyList(),
    val todayText: String = "今日 0 分钟",
    val totalText: String = "累计 0 小时 0 分",
    val loading: Boolean = false,
    val error: String? = null,
    /** 点击续播：Fragment 据此调播放引擎并导航。 */
    val pendingResume: HistoryEntity? = null,
    val message: String? = null
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val locator = (app as YunpanApp).locator
    private val historyDao: HistoryDao = locator.historyDao
    private val listenDao: ListenDao = locator.listenDao
    private val accountStore: AccountStore = locator.accountStore

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val dayStart = Fmt.dayStart(System.currentTimeMillis())
            val items = runCatching { historyDao.allByRecent() }.getOrDefault(emptyList())
            val today = runCatching { listenDao.todayMs(dayStart) }.getOrDefault(0L)
            val total = runCatching { listenDao.totalMs() }.getOrDefault(0L)
            _state.value = _state.value.copy(
                loading = false,
                items = items,
                todayText = "今日 ${Fmt.stats(today)}",
                totalText = "累计 ${Fmt.stats(total)}"
            )
        }
    }

    /**
     * 点击历史续播（v1.3：不再依赖进程内分享会话）。
     * SHARE 直链约 8h 过期：历史表已存 shareLinkId/sharePasswd/shareParentToken，
     * 播放时由 DefaultPlaybackResolver 按坐标重新 openShare + 列目录取新鲜 presentURL。
     */
    fun onItemClicked(item: HistoryEntity) {
        _state.value = _state.value.copy(pendingResume = item)
    }

    /** 续播前确保该历史属于的账号为 active（CLOUD139）。 */
    suspend fun prepareResumeAccount(item: HistoryEntity) {
        if (item.source == FileSource.CLOUD139.name) {
            val active = accountStore.active()
            if (active?.id != item.accountId) {
                runCatching { accountStore.setActive(item.accountId) }
            }
        }
    }

    fun delete(item: HistoryEntity) {
        viewModelScope.launch {
            historyDao.deleteByFileId(item.fileId)
            load()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            historyDao.clear()
            load()
        }
    }

    /** 批量删除选中历史。 */
    fun deleteByFileIds(fileIds: List<String>) {
        viewModelScope.launch {
            fileIds.forEach { historyDao.deleteByFileId(it) }
            load()
        }
    }

    /** 清除指定时间之前的历史（ms 时间戳）。 */
    fun clearOlderThan(cutoffMs: Long) {
        viewModelScope.launch {
            runCatching {
                historyDao.allByRecent().filter { it.lastPlayedAt < cutoffMs }
                    .forEach { historyDao.deleteByFileId(it.fileId) }
            }
            load()
        }
    }

    fun onPendingResumeConsumed() {
        _state.value = _state.value.copy(pendingResume = null)
    }

    fun onMessageConsumed() {
        _state.value = _state.value.copy(message = null)
    }

    /** 仅供 UI 构造续播 CloudFile。 */
    fun toResumeFile(item: HistoryEntity) = HistoryMapper.toResumeFile(item)
}
