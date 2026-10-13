package com.yunpan.tingshu.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.cloud139.Cloud139Repository
import com.yunpan.tingshu.cloud139.share.ShareRepository
import com.yunpan.tingshu.data.AccountStore
import com.yunpan.tingshu.data.SettingsStore
import com.yunpan.tingshu.data.db.BookshelfEntity
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.data.model.ScannedTrack
import com.yunpan.tingshu.data.model.ScanProgress
import com.yunpan.tingshu.ui.common.BrowseReducer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 浏览来源：个人云盘账号 或 无登录分享。 */
sealed class BrowseSource {
    abstract val title: String
    data class Cloud(val account: CloudAccount) : BrowseSource() {
        override val title: String = "我的云盘"
    }
    data class Share(val linkId: String, val passwd: String, override val title: String) : BrowseSource()
}

data class BreadcrumbEntry(val token: String, val name: String)

/** 扫描进度对话框状态。 */
data class ScanState(
    val folderName: String,
    val visitedFolders: Int,
    val foundTracks: Int,
    val active: Boolean = true
)

data class BrowseUiState(
    val source: BrowseSource? = null,
    val needsAccount: Boolean = false,
    val breadcrumb: List<BreadcrumbEntry> = listOf(BreadcrumbEntry("/", "根目录")),
    val items: List<CloudFile> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
    val scan: ScanState? = null,
    /** 加入书架等轻提示。 */
    val message: String? = null,
    /** 扫描完成待播放：Fragment 据此调播放引擎并进播放页。 */
    val pendingPlay: PendingPlay? = null,
    /** 点击当前层某音频待播放（index 指向 audioOnly 列表）。 */
    val pendingCurrentPlay: PendingCurrentPlay? = null
) {
    data class PendingPlay(val tracks: List<ScannedTrack>, val albumTitle: String)
    data class PendingCurrentPlay(val tracks: List<ScannedTrack>, val startIndex: Int, val albumTitle: String)
}

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val ROOT_TOKEN = "/"
        private const val SHARE_ROOT = "root"
    }

    private val locator = (app as YunpanApp).locator
    private val accountStore: AccountStore = locator.accountStore
    private val cloudRepo: Cloud139Repository = locator.cloud139Repository
    private val shareRepo: ShareRepository = locator.shareRepository
    private val settings: SettingsStore = locator.settingsStore

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    // 分页游标
    private var cloudCursor: String = ""
    private var shareNextBNum: Int = 1
    private var shareTotal: Int = Int.MAX_VALUE

    // 扫描 LRU 缓存：key=来源+token+depth
    private val scanCache = linkedMapOf<String, List<ScannedTrack>>()
    private var scanJob: Job? = null

    /** 进入页面初始化/恢复：优先消费分享会话，否则取 active 账号。 */
    fun initialize() {        if (_state.value.source != null) return
        viewModelScope.launch {
            val pending = locator.shareSession.consume()
            if (pending != null) {
                applySource(BrowseSource.Share(pending.linkId, pending.passwd, pending.title))
                loadRoot()
                return@launch
            }
            val active = accountStore.active()
            // 预检测：无账号 或 账号已过期 → 直接切到登录状态，不浪费网络请求
            if (active == null || active.isExpired) {
                _state.value = _state.value.copy(
                    needsAccount = true,
                    loading = false,
                    error = if (active?.isExpired == true) "登录已过期，请重新登录" else null
                )
            } else {
                applySource(BrowseSource.Cloud(active))
                loadRoot()
            }
        }
    }

    /** 从我的页加账号后回到浏览页时补加载。 */
    fun reloadIfNeeded() {
        val s = _state.value
        if (s.source == null && !s.needsAccount) {
            initialize()
        } else if (s.needsAccount) {
            viewModelScope.launch {
                val active = accountStore.active()
                if (active != null) {
                    _state.value = _state.value.copy(needsAccount = false)
                    applySource(BrowseSource.Cloud(active))
                    loadRoot()
                }
            }
        }
    }

    /** 顶部来源切换：切到个人云盘（当前账号）。 */
    fun switchToCloud() {
        viewModelScope.launch {
            val active = accountStore.active()
            if (active == null) {
                _state.value = _state.value.copy(needsAccount = true)
                return@launch
            }
            applySource(BrowseSource.Cloud(active))
            loadRoot()
        }
    }

    /** 顶部来源切换：切到某个已保存分享源。 */
    fun switchToShare(saved: com.yunpan.tingshu.data.SavedShare) {
        viewModelScope.launch {
            applySource(BrowseSource.Share(saved.linkId, saved.passwd, saved.name))
            loadRoot()
        }
    }

    /** 加入书架：文件夹行"书签"按钮，按当前来源保存坐标（v1.3）。 */
    fun addToBookshelf(folder: CloudFile) {
        if (!folder.isDir) return
        val source = _state.value.source ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val coordinate = folder.childToken ?: folder.id
            val entity = when (source) {
                is BrowseSource.Cloud -> BookshelfEntity(
                    source = FileSource.CLOUD139.name,
                    accountId = source.account.id,
                    folderId = coordinate,
                    folderName = folder.name,
                    albumPath = folder.albumPath.joinToString("/"),
                    addedAtMs = now,
                    updatedAtMs = now
                )
                is BrowseSource.Share -> BookshelfEntity(
                    source = FileSource.SHARE.name,
                    shareLinkId = source.linkId,
                    sharePasswd = source.passwd,
                    folderId = coordinate,
                    folderName = folder.name,
                    albumPath = folder.albumPath.joinToString("/"),
                    addedAtMs = now,
                    updatedAtMs = now
                )
            }
            val existed = runCatching {
                locator.bookshelfDao.findByCoordinates(entity.source, entity.folderId)
            }.getOrDefault(null)
            runCatching { locator.bookshelfDao.upsert(entity) }
            _state.value = _state.value.copy(
                message = (if (existed != null) "已在书架中：" else "已加入书架：") + folder.name
            )
        }
    }

    fun onMessageConsumed() {
        _state.value = _state.value.copy(message = null)
    }

    private fun applySource(source: BrowseSource) {
        cloudCursor = ""
        shareNextBNum = 1
        shareTotal = Int.MAX_VALUE
        _state.value = _state.value.copy(
            source = source,
            breadcrumb = listOf(BreadcrumbEntry(ROOT_TOKEN, source.title))
        )
    }

    fun refresh() {
        val s = _state.value
        if (s.source == null) {
            initialize()
            return
        }
        loadRoot(refreshing = true)
    }

    private fun loadRoot(refreshing: Boolean = false) {
        val entry = _state.value.breadcrumb.last()
        loadPage(entry.token, refreshing = refreshing, resetList = true)
    }

    private fun currentToken(): String = _state.value.breadcrumb.last().token

    private fun loadPage(token: String, refreshing: Boolean = false, resetList: Boolean = false, loadingMore: Boolean = false) {
        val source = _state.value.source ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = !refreshing && !loadingMore && !resetList,
                refreshing = refreshing,
                loadingMore = loadingMore,
                error = null
            )
            val result = runCatching {
                when (source) {
                    is BrowseSource.Cloud -> {
                        val page = cloudRepo.listFolder(source.account, token, if (resetList) "" else cloudCursor)
                        cloudCursor = page.nextCursor
                        shareTotal = Int.MAX_VALUE
                        // 加载更多时把新页追加到现有列表，不能整页替换（否则滚到底列表变白）
                        val merged = if (resetList) page.items
                        else BrowseReducer.appendPage(_state.value.items, page.items)
                        BrowseReducer.foldersFirst(merged) to page.nextCursor.isNotEmpty()
                    }
                    is BrowseSource.Share -> {
                        val bNum = if (resetList) 1 else shareNextBNum
                        val listing = shareRepo.listShareFolder(
                            source.linkId, toSharePcaId(token), source.passwd, bNum, bNum + 199
                        )
                        shareNextBNum = bNum + 200
                        shareTotal = listing.nodNum
                        val merged = if (resetList) listing.items
                        else BrowseReducer.appendPage(_state.value.items, listing.items)
                        BrowseReducer.foldersFirst(merged) to (merged.size < listing.nodNum)
                    }
                }
            }
            result.onSuccess { (items, hasMore) ->
                _state.value = _state.value.copy(
                    items = items,
                    loading = false, refreshing = false, loadingMore = false,
                    endReached = !hasMore
                )
            }.onFailure { e ->
                when (e) {
                    // Token 过期：切到需要登录状态，引导用户重新登录
                    is com.yunpan.tingshu.cloud139.TokenExpiredException -> {
                        _state.value = _state.value.copy(
                            loading = false, refreshing = false, loadingMore = false,
                            error = e.message ?: "登录已过期，请重新登录",
                            needsAccount = true
                        )
                    }
                    // 分享链接失效：明确提示
                    is com.yunpan.tingshu.cloud139.share.ShareLinkInvalidException -> {
                        _state.value = _state.value.copy(
                            loading = false, refreshing = false, loadingMore = false,
                            error = e.message ?: "分享链接已失效，请检查链接或提取码"
                        )
                    }
                    else -> {
                        _state.value = _state.value.copy(
                            loading = false, refreshing = false, loadingMore = false,
                            error = e.message ?: "加载失败，请下拉重试"
                        )
                    }
                }
            }
        }
    }

    /** 分享列目录：root 用字面量 "root"，子目录用其 childToken。 */
    private fun toSharePcaId(token: String): String =
        if (token == ROOT_TOKEN) SHARE_ROOT else token

    fun enterFolder(folder: CloudFile) {
        val token = folder.childToken ?: folder.id
        // 相邻同名段去重：分享根名与首个同名文件夹会叠加，避免面包屑重复
        val last = _state.value.breadcrumb.lastOrNull()
        if (last != null && last.name.trim() == folder.name.trim()) {
            // 同名同级：仅更新当前 token（保持层级，不重复追加）
            _state.value = _state.value.copy(
                breadcrumb = _state.value.breadcrumb.dropLast(1) + BreadcrumbEntry(token, folder.name)
            )
        } else {
            _state.value = _state.value.copy(
                breadcrumb = _state.value.breadcrumb + BreadcrumbEntry(token, folder.name)
            )
        }
        loadPage(token, resetList = true)
    }

    fun navigateBreadcrumb(index: Int) {
        val bc = _state.value.breadcrumb
        if (index < 0 || index >= bc.size - 1) return
        val newCrumb = bc.subList(0, index + 1).toList()
        _state.value = _state.value.copy(breadcrumb = newCrumb)
        loadPage(newCrumb.last().token, resetList = true)
    }

    /** 已取消分页：滚到底不再发网络请求。 */
    fun loadMore() {
        _state.value = _state.value.copy(endReached = true, loadingMore = false)
    }

    /** 点击音频：以当前层音频构建队列并定位到点击项。 */
    fun onAudioClicked(file: CloudFile) {
        val all = BrowseReducer.audioOnly(_state.value.items)
        val index = all.indexOfFirst { it.id == file.id }
        if (index < 0) return
        val crumb = _state.value.breadcrumb
        val albumTitle = crumb.last().name
        val tracks = BrowseReducer.toScannedTracks(albumTitle, all)
        _state.value = _state.value.copy(pendingCurrentPlay = BrowseUiState.PendingCurrentPlay(tracks, index, albumTitle))
    }

    /**
     * 播放整个文件夹：按深度扫描，带可取消进度。
     * @param folder 目标文件夹（null 表示当前层容器）
     */
    fun playWholeFolder(folder: CloudFile?, depth: ScanDepth) {
        val source = _state.value.source ?: return
        val token = folder?.let { it.childToken ?: it.id } ?: currentToken()
        val albumTitle = folder?.name ?: _state.value.breadcrumb.last().name
        val cacheKey = "${source::class.simpleName}|$token|${depth.name}"
        scanCache[cacheKey]?.let { cached ->
            _state.value = _state.value.copy(
                pendingPlay = BrowseUiState.PendingPlay(cached, albumTitle)
            )
            return
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(scan = ScanState(albumTitle, 0, 0))
            val result = runCatching {
                when (source) {
                    is BrowseSource.Cloud -> cloudRepo.scanAudio(source.account, token, depth) { onScanProgress(it) }
                    is BrowseSource.Share -> shareRepo.scanShareAudio(source.linkId, source.passwd, depth) { onScanProgress(it) }
                }
            }
            result.onSuccess { tracks ->
                putCache(cacheKey, tracks)
                _state.value = _state.value.copy(scan = null)
                if (tracks.isEmpty()) {
                    _state.value = _state.value.copy(error = "该文件夹下未找到音频")
                } else {
                    _state.value = _state.value.copy(
                        pendingPlay = BrowseUiState.PendingPlay(tracks, albumTitle)
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    scan = null,
                    error = if (isUserCancellation(e)) null else (e.message ?: "扫描失败")
                )
            }
        }
    }

    private fun onScanProgress(p: ScanProgress) {
        _state.value = _state.value.copy(
            scan = ScanState(p.currentFolder, p.visitedFolders, p.foundTracks)
        )
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.value = _state.value.copy(scan = null)
    }

    private fun isUserCancellation(e: Throwable): Boolean =
        e is kotlinx.coroutines.CancellationException

    private fun putCache(key: String, tracks: List<ScannedTrack>) {
        scanCache[key] = tracks
        // 简易 LRU：超过 16 条淘汰最旧
        while (scanCache.size > 16) scanCache.remove(scanCache.keys.first())
    }

    suspend fun defaultDepth(): ScanDepth = settings.defaultDepth.first()

    /** 浏览页勾选「记住为默认」时写入 DataStore。 */
    fun setDefaultDepthFromFragment(depth: ScanDepth) {
        viewModelScope.launch { settings.setDefaultDepth(depth) }
    }

    fun onPendingPlayConsumed() {
        _state.value = _state.value.copy(pendingPlay = null)
    }

    fun onPendingCurrentPlayConsumed() {
        _state.value = _state.value.copy(pendingCurrentPlay = null)
    }

    fun onErrorConsumed() {
        _state.value = _state.value.copy(error = null)
    }
}
