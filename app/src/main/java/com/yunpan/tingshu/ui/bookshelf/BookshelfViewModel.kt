package com.yunpan.tingshu.ui.bookshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.cloud139.Cloud139Repository
import com.yunpan.tingshu.cloud139.share.ShareRepository
import com.yunpan.tingshu.data.AccountStore
import com.yunpan.tingshu.data.db.BookshelfDao
import com.yunpan.tingshu.data.db.BookshelfEntity
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.data.model.ScannedTrack
import com.yunpan.tingshu.player.PlayOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 书架页状态。 */
data class BookshelfUiState(
    val items: List<BookshelfEntity> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** 扫描完成待播放：Fragment 据此调播放引擎并进播放页。 */
    val pendingPlay: PendingPlay? = null
) {
    data class PendingPlay(
        val tracks: List<ScannedTrack>,
        val albumTitle: String,
        val origin: PlayOrigin
    )
}

/**
 * 本地书单（书架）ViewModel（v1.3）。
 * - 收藏来源：浏览页文件夹行"加入书架"（BrowseViewModel.addToBookshelf）。
 * - 播放：按保存坐标重新解析——CLOUD139 切账号后 scanAudio；SHARE 重新 openShare 后 scanShareAudio。
 * - 操作：重命名 / 删除 / 下拉刷新（重新校验坐标可用）。
 */
class BookshelfViewModel(app: Application) : AndroidViewModel(app) {

    private val locator = (app as YunpanApp).locator
    private val bookshelfDao: BookshelfDao = locator.bookshelfDao
    private val playlistDao: com.yunpan.tingshu.data.db.PlaylistDao = locator.playlistDao
    private val accountStore: AccountStore = locator.accountStore
    private val cloudRepo: Cloud139Repository = locator.cloud139Repository
    private val shareRepo: ShareRepository = locator.shareRepository

    private val _state = MutableStateFlow(BookshelfUiState())
    val state: StateFlow<BookshelfUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val items = runCatching { bookshelfDao.all() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(items = items)
        }
    }

    /** 点击播放：按坐标重新解析并整书扫描播放。 */
    fun play(item: BookshelfEntity) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = runCatching {
                when (item.source) {
                    FileSource.CLOUD139.name -> {
                        val accountId = item.accountId
                            ?: throw IllegalStateException("该书单缺少账号信息，请删除后重新收藏")
                        if (accountStore.active()?.id != accountId) accountStore.setActive(accountId)
                        val account = accountStore.active()
                            ?: throw IllegalStateException("账号不存在或已过期，请重新登录")
                        cloudRepo.scanAudio(account, item.folderId, ScanDepth.ALL)
                    }
                    FileSource.SHARE.name -> {
                        val linkId = item.shareLinkId
                            ?: throw IllegalStateException("该书单缺少分享链接，请删除后重新收藏")
                        shareRepo.scanShareAudio(linkId, item.sharePasswd ?: "", ScanDepth.ALL)
                    }
                    else -> throw IllegalStateException("未知来源")
                }
            }
            result.onSuccess { tracks ->
                _state.value = _state.value.copy(loading = false)
                if (tracks.isEmpty()) {
                    _state.value = _state.value.copy(error = "该书单下未找到音频，可能已被移除，可下拉刷新")
                } else {
                    val origin = if (item.source == FileSource.SHARE.name) {
                        PlayOrigin.Share(item.shareLinkId ?: "", item.sharePasswd ?: "", item.folderName)
                    } else {
                        PlayOrigin.Cloud
                    }
                    runCatching { bookshelfDao.touch(item.id, System.currentTimeMillis()) }
                    _state.value = _state.value.copy(
                        pendingPlay = BookshelfUiState.PendingPlay(tracks, item.folderName, origin)
                    )
                }
            }.onFailure { e ->
                // Token 过期给更明确的引导
                val errorMsg = when (e) {
                    is com.yunpan.tingshu.cloud139.TokenExpiredException ->
                        (e.message ?: "登录已过期") + "，请去「我的」页重新登录后再试"
                    else -> e.message ?: "播放失败，请检查网络或链接是否失效"
                }
                _state.value = _state.value.copy(
                    loading = false,
                    error = errorMsg
                )
            }
        }
    }

    /** 重命名。 */
    fun rename(item: BookshelfEntity, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == item.folderName) return
        viewModelScope.launch {
            runCatching { bookshelfDao.rename(item.id, name, System.currentTimeMillis()) }
            load()
        }
    }

    fun delete(item: BookshelfEntity) {
        viewModelScope.launch {
            runCatching { bookshelfDao.deleteById(item.id) }
            load()
        }
    }

    /** 批量删除选中的书架条目。 */
    fun deleteByIds(ids: List<Long>) {
        viewModelScope.launch {
            runCatching { ids.forEach { bookshelfDao.deleteById(it) } }
            load()
        }
    }

    /** 一键清空书架。 */
    fun clearAll() {
        viewModelScope.launch {
            runCatching { bookshelfDao.all().forEach { bookshelfDao.deleteById(it.id) } }
            load()
        }
    }

    /** 下拉刷新：逐个重新校验坐标（列第一页），更新校验时间；失效条目给出统计。 */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val items = runCatching { bookshelfDao.all() }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            var ok = 0
            for (it in items) {
                val success = runCatching {
                    when (it.source) {
                        FileSource.CLOUD139.name -> {
                            val accountId = it.accountId ?: return@runCatching false
                            if (accountStore.active()?.id != accountId) accountStore.setActive(accountId)
                            val acc = accountStore.active() ?: return@runCatching false
                            cloudRepo.listFolder(acc, it.folderId, "")
                            true
                        }
                        FileSource.SHARE.name -> {
                            val linkId = it.shareLinkId ?: return@runCatching false
                            shareRepo.listShareFolder(linkId, it.folderId, it.sharePasswd ?: "", 1, 1)
                            true
                        }
                        else -> false
                    }
                }.getOrDefault(false)
                if (success) {
                    runCatching { bookshelfDao.touch(it.id, now) }
                    ok++
                }
            }
            load()
            _state.value = _state.value.copy(
                loading = false,
                message = if (ok == items.size) "书架已刷新" else "刷新完成：$ok/${items.size} 本可访问"
            )
        }
    }

    fun onPendingPlayConsumed() {
        _state.value = _state.value.copy(pendingPlay = null)
    }

    fun onMessageConsumed() {
        _state.value = _state.value.copy(message = null)
    }

    fun onErrorConsumed() {
        _state.value = _state.value.copy(error = null)
    }

    // ---- 命名歌单 ----

    /** 创建歌单，返回新 id（失败返回 null）。 */
    suspend fun createPlaylist(name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            playlistDao.insertPlaylist(
                com.yunpan.tingshu.data.db.PlaylistEntity(name = trimmed, createdAt = System.currentTimeMillis())
            )
        }.getOrNull()
    }

    /** 所有歌单（带每单数量）。 */
    suspend fun listPlaylists(): List<com.yunpan.tingshu.data.db.PlaylistEntity> =
        runCatching { playlistDao.allPlaylists() }.getOrDefault(emptyList())

    /** 把书架条目加入指定歌单；返回提示文案。 */
    fun addToPlaylist(playlistId: Long, item: BookshelfEntity) {
        viewModelScope.launch {
            runCatching {
                playlistDao.addItem(
                    com.yunpan.tingshu.data.db.PlaylistItemEntity(
                        playlistId = playlistId, bookshelfId = item.id, addedAt = System.currentTimeMillis()
                    )
                )
            }
            _state.value = _state.value.copy(message = "已把「${item.folderName}」加入歌单")
        }
    }

    /** 某歌单内的书架条目。 */
    suspend fun playlistBooks(playlistId: Long): List<BookshelfEntity> {
        val ids = runCatching { playlistDao.itemBookIds(playlistId) }.getOrDefault(emptyList())
        return ids.mapNotNull { runCatching { bookshelfDao.getById(it) }.getOrNull() }
    }

    /** 进度百分比 0..100（展示用）。 */
    fun progressPercent(item: BookshelfEntity): Int {
        if (item.totalMs <= 0) return 0
        return ((item.positionMs * 100.0) / item.totalMs).toInt().coerceIn(0, 100)
    }
}

/** 供 BrowseViewModel 构造书架条目用的窄接口（避免直接依赖 CloudFile 全字段）。 */
fun CloudFile.toBookshelfEntity(
    source: FileSource,
    accountId: String?,
    shareLinkId: String?,
    sharePasswd: String?,
    folderCoordinate: String,
    nowMs: Long
): BookshelfEntity = BookshelfEntity(
    source = source.name,
    accountId = accountId,
    shareLinkId = shareLinkId,
    sharePasswd = sharePasswd,
    folderId = folderCoordinate,
    folderName = name,
    albumPath = albumPath.joinToString("/"),
    addedAtMs = nowMs,
    updatedAtMs = nowMs
)
