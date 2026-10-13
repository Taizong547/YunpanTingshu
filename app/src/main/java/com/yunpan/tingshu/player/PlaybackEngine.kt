package com.yunpan.tingshu.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.yunpan.tingshu.data.ProgressStore
import com.yunpan.tingshu.data.cover.ArtworkRef
import com.yunpan.tingshu.data.cover.CoverArtProvider
import com.yunpan.tingshu.data.db.HistoryDao
import com.yunpan.tingshu.data.db.ListenDao
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.PlaybackResolver
import com.yunpan.tingshu.player.session.SavedSession
import com.yunpan.tingshu.player.session.SessionSource
import com.yunpan.tingshu.player.session.SessionStore
import com.yunpan.tingshu.player.session.SessionTrack
import com.yunpan.tingshu.util.Clock
import com.yunpan.tingshu.util.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** 播放来源上下文（决定会话存 CLOUD 还是 SHARE）。 */
sealed class PlayOrigin {
    object Cloud : PlayOrigin()
    data class Share(val linkId: String, val passwd: String, val shareName: String) : PlayOrigin()
}

/** 音频焦点回调（测试可注入 fake）。 */
interface AudioFocusController {
    /** 请求焦点；返回是否立即获得。 */
    fun request(): Boolean
    fun abandon()
    fun setCallback(cb: Callback)

    interface Callback {
        fun onPermanentLoss()
        fun onTransientLoss()
        fun onGain()
    }
}

/**
 * 播放引擎：ExoPlayer + 自管队列 + 睡眠定时 + 历史上报 + 锁 + 焦点 + 错误自愈 + 会话持久化。
 */
class PlaybackEngine(
    private val context: Context,
    private val okHttp: okhttp3.OkHttpClient,
    private val resolver: PlaybackResolver,
    private val progressStore: ProgressStore,
    historyDao: HistoryDao,
    listenDao: ListenDao,
    private val accountProvider: suspend () -> String?,
    private val stateSink: MutableStateFlow<PlaybackState>,
    private val onTimerFired: () -> Unit,
    private val sessionStore: SessionStore,
    private val lockSet: LockSet,
    private val focus: AudioFocusController,
    private val recoverer: PlaybackRecoverer,
    private val coverArtProvider: CoverArtProvider? = null,
    private val analyticsSink: AlbumAnalyticsSink? = null,
    private val loudness: LoudnessController = AndroidLoudnessController(),
    private val gainStore: VolumeGainStore? = null,
    private val skipIntroMs: () -> Long = { 0L },
    private val skipOutroMs: () -> Long = { 0L },
    private val clock: Clock = SystemClock
) {
    // 全局兜底：播放链路任何协程异常都只记录、绝不拖垮进程。
    private val faultHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
        android.util.Log.e("PlaybackEngine", "uncaught in playback scope", t)
    }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + faultHandler
    )

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setHandleAudioBecomingNoisy(true)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
                .build()
        )
        .build()

    private val queue = PlayerQueue()
    val timer = SleepTimer(clock)
    private val reporter = PlaybackReporter(historyDao, listenDao, progressStore, clock, analyticsSink)
    private val tracks = ArrayList<CloudFile>()

    @Volatile private var volumeGainDb: Int = 0
    @Volatile private var outroHandled: Boolean = false

    private var origin: PlayOrigin = PlayOrigin.Cloud
    private var albumTitle: String = ""
    private var ticksSinceSave: Int = 0
    private var released: Boolean = false
    private var wasPlayingBeforeFocusLoss: Boolean = false
    private var recovering: Boolean = false

    init {
        focus.setCallback(object : AudioFocusController.Callback {
            override fun onPermanentLoss() = pause()
            override fun onTransientLoss() { wasPlayingBeforeFocusLoss = player.isPlaying; pause() }
            override fun onGain() { if (wasPlayingBeforeFocusLoss) resume() }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                runCatching {
                    pushState()
                    if (state == Player.STATE_ENDED) onTrackNaturallyEnded()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                runCatching {
                    pushState()
                    if (isPlaying) { lockSet.acquire(); focus.request() } else lockSet.release()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                runCatching { onError(error) }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                runCatching {
                    loudness.attach(audioSessionId)
                    loudness.setGainMB(VolumeGain.toMilliBels(volumeGainDb))
                }
            }
        })
        // 启动恢复响度档位
        scope.launch {
            volumeGainDb = VolumeGain.clampDb(gainStore?.load() ?: 0)
            loudness.setGainMB(VolumeGain.toMilliBels(volumeGainDb))
            pushState()
        }
        scope.launch {
            timer.state.collect { ts ->
                if (ts == TimerUiState.Fired) handleTimerFired()
                pushState()
            }
        }
        startTickLoop()
    }

    // ---------- 对外命令 ----------

    fun playTracks(tracks: List<CloudFile>, startIndex: Int, albumTitle: String, origin: PlayOrigin = PlayOrigin.Cloud) {
        if (tracks.isEmpty()) { setError("没有可播放的音频"); return }
        this.origin = origin
        this.albumTitle = albumTitle
        this.tracks.clear(); this.tracks.addAll(tracks)
        queue.setList(tracks, startIndex)
        recoverer.reset()
        scope.launch { playCurrent(isAuto = false) }
    }

    /** 冷启动恢复：重建队列元数据，prepare 到暂停态或直接播放。 */
    suspend fun resumeLast(session: SavedSession, autoplay: Boolean) {
        origin = when (session.source) {
            SessionSource.CLOUD -> PlayOrigin.Cloud
            SessionSource.SHARE -> PlayOrigin.Share(
                session.linkId ?: "", session.passwd ?: "", session.shareName ?: ""
            )
        }
        albumTitle = session.albumTitle
        queue.setMode(session.playMode)
        player.setPlaybackSpeed(session.speed)
        val rebuilt = session.tracks.map { it.toCloudFile(origin) }
        tracks.clear(); tracks.addAll(rebuilt)
        val start = session.index.coerceIn(0, (rebuilt.size - 1).coerceAtLeast(0))
        queue.setList(rebuilt, start)
        playCurrent(isAuto = false, resumePosition = session.positionMs, autoPlay = autoplay)
    }

    fun pause() {
        player.pause()
        lockSet.release()
        saveProgressNow()
        persistSession()
        pushState()
    }

    fun resume() {
        if (!focus.request()) return
        player.play()
        lockSet.acquire()
        pushState()
    }

    fun toggle() { if (player.isPlaying) pause() else resume() }

    fun next() {
        timer.manualAdvance()
        if (timer.state.value == TimerUiState.Fired) return
        val target = queue.next()
        if (target == null) { player.pause(); saveProgressNow(); persistSession(); return }
        recoverer.reset()
        scope.launch { reporter.onEpisodeConsumed(); playCurrent(isAuto = false) }
    }

    fun prev() {
        val target = queue.prev()
        if (target == null) { player.seekTo(0); return }
        recoverer.reset()
        scope.launch { playCurrent(isAuto = false) }
    }

    fun seekTo(ms: Long) { player.seekTo(ms); pushState() }

    fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed); persistSession(); pushState() }

    /** 设置响度增益档位(dB)，clamp 到合法档位并持久化。 */
    fun setVolumeGain(db: Int) {
        volumeGainDb = VolumeGain.clampDb(db)
        loudness.setGainMB(VolumeGain.toMilliBels(volumeGainDb))
        scope.launch { gainStore?.save(volumeGainDb) }
        pushState()
    }

    /** 循环到下一档响度（OFF→+3→+6→+10→OFF）。 */
    fun cycleVolumeGain() = setVolumeGain(VolumeGain.nextDb(volumeGainDb))

    fun cyclePlayMode() {
        val next = when (queue.mode) {
            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
        }
        queue.setMode(next); persistSession(); pushState()
    }

    fun setSleep(cfg: TimerConfig) { timer.start(cfg); persistSession() }
    fun cancelSleep() { timer.cancel(); persistSession() }
    fun extendSleep() { timer.extend() }

    fun isCurrent(fileId: String): Boolean = queue.current()?.id == fileId

    /** 只读：当前队列快照（UI 队列面板用，不改变播放）。 */
    fun queueSnapshot(): List<CloudFile> = tracks.toList()

    /** 用户点队列列表某一项：移动指针并播放（复用 playCurrent）。 */
    fun jumpTo(index: Int) {
        if (index !in tracks.indices) return
        if (!queue.moveTo(index)) return
        recoverer.reset()
        scope.launch { playCurrent(isAuto = false) }
    }

    /** 是否有活跃队列（可恢复会话）。 */
    fun hasActiveSession(): Boolean = queue.size > 0

    fun stop() {
        saveProgressNow(); persistSession()
        player.pause(); player.stop()
        lockSet.release(); focus.abandon()
        queue.setList(emptyList(), 0); tracks.clear()
        pushState()
    }

    /** onTaskRemoved / 进后台：落盘会话但不释放播放器。 */
    fun onBackgrounded() { persistSession() }

    fun release() {
        released = true
        saveProgressNow(); persistSession()
        lockSet.release(); focus.abandon(); loudness.release()
        scope.cancel()
        player.release()
    }

    // ---------- 内部 ----------

    private fun onTrackNaturallyEnded() {
        timer.automaticAdvance()
        if (timer.state.value == TimerUiState.Fired) return
        val target = queue.next()
        if (target == null) { player.playWhenReady = false; saveProgressNow(); persistSession(); return }
        recoverer.reset()
        scope.launch { reporter.onEpisodeConsumed(); playCurrent(isAuto = true) }
    }

    private suspend fun playCurrent(
        isAuto: Boolean,
        resumePosition: Long? = null,
        autoPlay: Boolean = true,
        forceFresh: Boolean = false
    ) {
        val file = queue.current() ?: return
        try {
            val spec = if (forceFresh) resolver.resolveFresh(file) else resolver.resolve(file)
            val accountId = analyticsAccountId()
            val art = coverArtProvider?.artworkFor(file)
            val resume = resumePosition ?: resumePositionFor(file)
            reporter.onPlay(file, accountId, resume)
            val source = MediaSourceBuilder.build(okHttp, spec, file, albumTitle, art)
            runCatching {
                android.util.Log.d(
                    "PlaybackEngine",
                    "play ${file.name} | url=${spec.uri} | volume=${player.volume} | audioAttr=${player.audioAttributes.contentType}"
                )
            }
            player.volume = 1f
            player.setMediaSource(source)
            player.prepare()
            val startPos = SkipTiming.introSeekMs(resume, skipIntroMs())
            if (startPos > 0) player.seekTo(startPos)
            player.playWhenReady = autoPlay
            outroHandled = false
            ticksSinceSave = 0
            recoverer.noteSuccess()
            recovering = false
            stateSink.value = stateSink.value.copy(error = null)
            persistSession()
            pushState()
        } catch (t: Throwable) {
            android.util.Log.e("PlaybackEngine", "playCurrent failed: ${file.name}", t)
            // 立即把真实错误显示到播放页，不再静默（全新安装首次取直链/路由失败时用户能看到）
            stateSink.value = stateSink.value.copy(
                error = "获取播放地址失败，请检查网络后重试（${t.message ?: "未知错误"}）"
            )
            if (recovering) {
                // 自愈重入又失败：复位互斥并降级错误态，避免静默卡死（不闪退）
                recovering = false
                player.playWhenReady = false
                lockSet.release()
            } else {
                onError(t)
            }
        }
    }

    /** 分析上报账号：分享源用 "share:"+linkId，云端用当前账号 id。 */
    private suspend fun analyticsAccountId(): String = when (val o = origin) {
        is PlayOrigin.Share -> "share:" + o.linkId
        else -> accountProvider() ?: ""
    }

    /**
     * 播放错误分级自愈（v1.3）：
     * - 清单(.m3u8)鉴权/链接过期（401/403/404/410）→ 强制整源重开：resolveFresh 重新取直链 + seek，文案提示重开；
     * - 其余（分片边界抖动、传输错误）→ 原地重建源 + seek 续播，文案"网络缓冲，正在重连"，不再误报"直链过期"。
     * 两者都受 [recovering] 互斥保护，避免并发重入。
     */
    private fun onError(t: Throwable) {
        if (recovering) return

        if (StreamLoadErrorPolicy.isManifestAuthExpired(t)) {
            recovering = true
            val pos = player.currentPosition.coerceAtLeast(0)
            val wasPlaying = player.playWhenReady
            stateSink.value = stateSink.value.copy(error = "直链已过期，正在重新打开…")
            scope.launch {
                delay(MANIFEST_REOPEN_DELAY_MS)
                try {
                    playCurrent(isAuto = false, resumePosition = pos, autoPlay = wasPlaying, forceFresh = true)
                } finally {
                    recovering = false
                }
            }
            return
        }

        when (val d = recoverer.noteError()) {
            is RecoveryDecision.Retry -> {
                recovering = true
                val pos = player.currentPosition.coerceAtLeast(0)
                val wasPlaying = player.playWhenReady
                stateSink.value = stateSink.value.copy(error = "网络缓冲，正在重连…")
                scope.launch {
                    delay(d.backoffMs)
                    try {
                        playCurrent(isAuto = false, resumePosition = pos, autoPlay = wasPlaying)
                    } finally {
                        recovering = false
                    }
                }
            }
            RecoveryDecision.GiveUp -> {
                recovering = false
                stateSink.value = stateSink.value.copy(
                    error = "播放多次失败，请检查网络或重新进入（直链可能已过期）"
                )
                player.playWhenReady = false
                lockSet.release()
            }
        }
    }

    private suspend fun resumePositionFor(file: CloudFile): Long {
        val prog = progressStore.get(file.id) ?: return 0L
        if (prog.durationMs > 0 && prog.positionMs >= prog.durationMs * 0.95) return 0L
        return prog.positionMs.coerceAtLeast(0L)
    }

    private fun startTickLoop() {
        scope.launch {
            while (coroutineContext.isActive) {
                delay(1000)
                // 单次 tick 异常不杀掉整个循环，保证进度条持续更新
                runCatching {
                    timer.onTick()
                    if (released) return@runCatching
                    if (player.isPlaying) {
                        reporter.onElapsedPlaying(1000)
                        ticksSinceSave++
                        if (ticksSinceSave >= 4) { saveProgressNow(); persistSession(); ticksSinceSave = 0 }
                        // 片尾智能跳过：剩余 <= outroMs 且非单曲循环时自动切下一集
                        if (!outroHandled && queue.mode != PlayMode.REPEAT_ONE) {
                            val dur = if (player.duration == C.TIME_UNSET) 0L else player.duration
                            val pos = if (player.currentPosition == C.TIME_UNSET) 0L else player.currentPosition
                            if (SkipTiming.shouldAutoNext(pos, dur, skipOutroMs())) {
                                outroHandled = true
                                onTrackNaturallyEnded()
                            }
                        }
                    }
                    pushState()
                }
            }
        }
    }

    private fun saveProgressNow() {
        val file = queue.current() ?: return
        val pos = player.currentPosition.coerceAtLeast(0)
        val dur = if (player.duration > 0) player.duration else 0L
        scope.launch { reporter.onProgress(file.id, pos, dur) }
    }

    private var persistJob: kotlinx.coroutines.Job? = null
    private fun persistSession() {
        val cur = queue.current() ?: return
        val pos = player.currentPosition.coerceAtLeast(0)
        val stracks = tracks.map {
            SessionTrack(
                fileId = it.id, childToken = it.childToken, name = it.name,
                albumPath = it.albumPath, audioExt = it.audioExt,
                durationSec = it.durationSec, isHls = it.isHls,
                parentToken = it.parentToken ?: it.parentId.ifEmpty { null }
            )
        }
        val sleepStr = when (timer.state.value) {
            is TimerUiState.MinutesLeft -> "Minutes"
            TimerUiState.EndOfEpisodeActive -> "EndOfEpisode"
            is TimerUiState.EpisodesLeft -> "Episodes"
            else -> null
        }
        val session = SavedSession(
            source = if (origin is PlayOrigin.Cloud) SessionSource.CLOUD else SessionSource.SHARE,
            accountId = if (origin is PlayOrigin.Cloud) cur.id else null,
            linkId = (origin as? PlayOrigin.Share)?.linkId,
            passwd = (origin as? PlayOrigin.Share)?.passwd,
            shareName = (origin as? PlayOrigin.Share)?.shareName,
            albumTitle = albumTitle,
            rootToken = null,
            tracks = stracks,
            index = queue.index,
            positionMs = pos,
            playMode = queue.mode,
            speed = player.playbackParameters.speed,
            sleepConfig = sleepStr,
            updatedAt = clock.nowMs()
        )
        persistJob?.cancel()
        persistJob = scope.launch { sessionStore.save(session) }
    }

    private fun setError(msg: String) { stateSink.value = stateSink.value.copy(error = msg) }

    private fun handleTimerFired() {
        player.playWhenReady = false; player.pause()
        lockSet.release(); focus.abandon()
        saveProgressNow(); persistSession()
        onTimerFired()
        pushState()
    }

    private fun pushState() {
        if (released) return
        val file = queue.current()
        stateSink.value = PlaybackState(
            current = file,
            position = queue.index,
            size = queue.size,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = if (player.currentPosition == C.TIME_UNSET) 0 else player.currentPosition,
            // ExoPlayer 未解析出时长（流式/HLS 缓冲中）时，回退到清单已知时长，避免显示 00:00
            durationMs = run {
                val exo = if (player.duration == C.TIME_UNSET) 0L else player.duration
                if (exo > 0L) exo else (file?.durationSec ?: 0L) * 1000L
            },
            speed = player.playbackParameters.speed,
            playMode = queue.mode,
            timer = timer.state.value,
            albumTitle = albumTitle,
            error = stateSink.value.error,
            volumeGainDb = volumeGainDb,
            availableGainStages = VolumeGain.STAGES_DB
        )
    }

    companion object {
        /** 清单鉴权过期后，整体重开前的短暂延迟（避免立即风暴）。 */
        const val MANIFEST_REOPEN_DELAY_MS = 300L
    }
}

/** SessionTrack → 可播放的 CloudFile（URL 留空，播放时懒解析）。 */
private fun SessionTrack.toCloudFile(origin: PlayOrigin): CloudFile {
    val share = origin as? PlayOrigin.Share
    return CloudFile(
        id = fileId,
        name = name,
        isDir = false,
        size = 0,
        updatedAtMs = 0,
        thumbnailUrl = "",
        parentId = parentToken ?: "",
        audioExt = audioExt,
        source = if (share != null) FileSource.SHARE else FileSource.CLOUD139,
        childToken = childToken,
        directUrl = null,
        isHls = isHls,
        durationSec = durationSec,
        albumPath = albumPath,
        parentToken = parentToken,
        shareLinkId = share?.linkId,
        sharePasswd = share?.passwd
    )
}
