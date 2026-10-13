package com.yunpan.tingshu.player

import android.content.Context
import com.yunpan.tingshu.data.SettingsStore
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.ScannedTrack
import com.yunpan.tingshu.player.session.SavedSession
import com.yunpan.tingshu.player.session.SessionSource
import com.yunpan.tingshu.player.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 给 UI 展示“继续收听”卡片的恢复信息。 */
data class RestoredSessionInfo(
    val albumTitle: String,
    val firstTrackName: String,
    val positionMs: Long,
    val total: Int,
    val source: SessionSource
)

/**
 * app 级播放控制器（UI 唯一入口）。进程内持有 [PlaybackEngine] 引用。
 *
 * v2：新增冷启动续播——[restoredSession]/[hasRestorableSession]/[resumeLast]、[autoResumeOnLaunch]。
 */
class PlaybackManager(
    private val app: Context,
    private val sessionStore: SessionStore,
    private val settingsStore: SettingsStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val stateMutable = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = stateMutable.asStateFlow()

    private val _restored = MutableStateFlow<RestoredSessionInfo?>(null)
    val restoredSession: StateFlow<RestoredSessionInfo?> = _restored.asStateFlow()

    /** 智能跳过片头/片尾（毫秒），从 SettingsStore 同步；引擎读取快照。 */
    private val _skipIntroMs = MutableStateFlow(0L)
    val skipIntroMs: StateFlow<Long> = _skipIntroMs.asStateFlow()
    private val _skipOutroMs = MutableStateFlow(0L)
    val skipOutroMs: StateFlow<Long> = _skipOutroMs.asStateFlow()

    @Volatile private var engine: PlaybackEngine? = null
    @Volatile private var pending: PendingPlay? = null
    @Volatile private var pendingResume: Boolean? = null   // autoplay
    @Volatile var autoResumeOnLaunch: Boolean = false
        private set

    private var lastSession: SavedSession? = null

    private data class PendingPlay(
        val tracks: List<CloudFile>, val startIndex: Int, val albumTitle: String, val origin: PlayOrigin
    )

    init {
        // 启动即读会话
        scope.launch {
            val s = sessionStore.load()
            lastSession = s
            _restored.value = s?.let {
                RestoredSessionInfo(
                    albumTitle = it.albumTitle,
                    firstTrackName = it.currentTrack()?.name ?: "",
                    positionMs = it.positionMs,
                    total = it.tracks.size,
                    source = it.source
                )
            }
        }
        // 智能跳过配置同步（秒→毫秒）
        val ss = settingsStore
        if (ss != null) {
            scope.launch { ss.skipIntroMsFlow().collectLatest { _skipIntroMs.value = it } }
            scope.launch { ss.skipOutroMsFlow().collectLatest { _skipOutroMs.value = it } }
        }
    }

    internal fun attach(e: PlaybackEngine) {
        engine = e
        pending?.let { e.playTracks(it.tracks, it.startIndex, it.albumTitle, it.origin) }
        pending = null
        pendingResume?.let { autoplay ->
            val s = lastSession
            if (s != null) scope.launch {
                val result = runCatching { e.resumeLast(s, autoplay) }
                // token 过期导致恢复失败：清空 session，避免下次启动重复失败
                result.onFailure { err ->
                    if (err is com.yunpan.tingshu.cloud139.TokenExpiredException) {
                        runCatching { sessionStore.clear() }
                        lastSession = null
                        _restored.value = null
                    }
                }
                pendingResume = null
            }
        }
    }

    internal fun detach() {
        engine = null
        stateMutable.value = PlaybackState()
    }

    // ---------- UI 命令 ----------

    /** 以一组音频播放。[origin] 标记来源（云端默认；分享传 PlayOrigin.Share）。 */
    fun playTracks(
        tracks: List<CloudFile>,
        startIndex: Int,
        albumTitle: String,
        origin: PlayOrigin = PlayOrigin.Cloud
    ) {
        val e = engine
        if (e != null) e.playTracks(tracks, startIndex, albumTitle, origin)
        else {
            pending = PendingPlay(tracks, startIndex, albumTitle, origin)
            PlaybackService.start(app)
        }
    }

    fun playScannedTracks(
        tracks: List<ScannedTrack>, startIndex: Int, albumTitle: String,
        origin: PlayOrigin = PlayOrigin.Cloud
    ) {
        playTracks(tracks.map { it.file }, startIndex, albumTitle, origin)
    }

    fun pause() { engine?.pause() }
    fun resume() { engine?.resume() }
    fun toggle() { engine?.toggle() }
    fun next() { engine?.next() }
    fun prev() { engine?.prev() }
    fun seekTo(ms: Long) { engine?.seekTo(ms) }
    fun setSpeed(speed: Float) { engine?.setSpeed(speed) }
    fun setVolumeGain(db: Int) { engine?.setVolumeGain(db) }
    fun cycleVolumeGain() { engine?.cycleVolumeGain() }
    fun cyclePlayMode() { engine?.cyclePlayMode() }
    fun setSleep(cfg: TimerConfig) { engine?.setSleep(cfg) }
    fun cancelSleep() { engine?.cancelSleep() }
    fun extendSleep() { engine?.extendSleep() }
    fun stop() { engine?.stop() }

    fun isCurrent(fileId: String): Boolean = engine?.isCurrent(fileId) == true

    /** 只读：当前队列快照（队列面板用）。 */
    fun queueSnapshot(): List<CloudFile> = engine?.queueSnapshot() ?: emptyList()

    /** 点击队列列表某项跳转播放。 */
    fun jumpTo(index: Int) { engine?.jumpTo(index) }

    // ---------- 冷启动续播 ----------

    fun hasRestorableSession(): Boolean = lastSession != null

    /** 重建队列并 seek 到保存位置；autoplay=true 直接播放，false 仅 prepare（迷你条）。 */
    fun resumeLast(autoplay: Boolean) {
        val s = lastSession ?: return
        val e = engine
        if (e != null) scope.launch { runCatching { e.resumeLast(s, autoplay) } }
        else {
            pendingResume = autoplay
            PlaybackService.start(app)
        }
    }

    fun setAutoResume(enabled: Boolean) { autoResumeOnLaunch = enabled }
}
