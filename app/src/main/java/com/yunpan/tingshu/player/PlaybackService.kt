package com.yunpan.tingshu.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.player.session.DataStoreSessionKv
import com.yunpan.tingshu.player.session.SessionStore
import com.yunpan.tingshu.ui.MainActivity
import com.yunpan.tingshu.util.SystemClock
import kotlinx.coroutines.flow.first

/**
 * 后台播放服务（MediaSessionService）。播放器唯一归属本服务；Activity 经 PlaybackManager 控制。
 *
 * onTaskRemoved：正在播放或有可恢复会话时先落盘会话、保活 Service（不 stopSelf）；
 * 仅当完全空闲（未播放、无队列）才允许停止。onDestroy 释放播放器与所有锁。
 */
class PlaybackService : MediaSessionService() {

    private var engine: PlaybackEngine? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // 立即满足 startForegroundService() 合同（Android 12+ 尤其 Android 14/15 要求约 5s 内
        // 调 startForeground，否则 ForegroundServiceDidNotStartInTimeException 崩溃）。
        // 用 Media3 相同的通知 id/渠道，随后被 MediaStyle 通知替换，不会出现双通知。
        runCatching { startForeground(NOTIF_ID, buildPlaceholderNotification()) }
        try {
            val locator = (application as? YunpanApp)?.locator ?: run {
                // locator 不可用时直接停服，避免后续 NPE 连环崩溃
                stopForeground(true)
                stopSelf()
                return
            }
            val e = PlaybackEngine(
                context = this,
                okHttp = locator.okHttp,
                resolver = locator.playbackResolver,
                progressStore = locator.progressStore,
                historyDao = locator.historyDao,
                listenDao = locator.listenDao,
                accountProvider = { locator.accountStore.active()?.id },
                stateSink = locator.playbackManager.stateMutable,
                onTimerFired = { stopSelf() },
                sessionStore = SessionStore(DataStoreSessionKv(this), SystemClock),
                lockSet = SystemLockSet(this),
                focus = AndroidAudioFocusController(this),
                recoverer = PlaybackRecoverer(SystemClock),
                coverArtProvider = locator.coverArtProvider,
                analyticsSink = AlbumAnalyticsSink { a, p, ms, ep, c ->
                    runCatching { locator.analyticsRepository.addAlbumPlayed(a, p, ms, ep, c) }
                },
                loudness = AndroidLoudnessController(),
                gainStore = object : VolumeGainStore {
                    override suspend fun load(): Int =
                        locator.settingsStore.volumeGainDb.first()
                    override suspend fun save(db: Int) =
                        runCatching { locator.settingsStore.setVolumeGainDb(db) }.let {}
                },
                skipIntroMs = { locator.playbackManager.skipIntroMs.value },
                skipOutroMs = { locator.playbackManager.skipOutroMs.value }
            )
            engine = e
            mediaSession = MediaSession.Builder(this, SessionPlayer(e.player, { e.next() }, { e.prev() }))
                .build()
            locator.playbackManager.attach(e)
        } catch (t: Throwable) {
            // 引擎装配失败（极端机型/资源不足）：落错误态并优雅停服，不崩溃整个进程
            runCatching {
                (application as? YunpanApp)?.locator?.playbackManager?.stateMutable
                    ?.value = PlaybackState(error = "播放引擎初始化失败，请重启 App")
            }
            stopForeground(true)
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉后台：先落盘会话；若在播或有队列则保活不 stopSelf。
        runCatching { engine?.onBackgrounded() }
        val e = engine
        val keepAlive = runCatching {
            e != null && (e.player.isPlaying || e.hasActiveSession())
        }.getOrDefault(false)
        if (!keepAlive) {
            runCatching { stopSelf() }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching {
            engine?.release()
            engine = null
            mediaSession?.release()
            mediaSession = null
            (application as? YunpanApp)?.locator?.playbackManager?.detach()
        }
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台播放控制" }
            nm.createNotificationChannel(ch)
        }
    }

    /** 占位前台通知（仅用于满足合同；Media3 就绪后用同 id 替换为完整 MediaStyle）。 */
    private fun buildPlaceholderNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("云盘听书")
            .setContentText("正在准备播放…")
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** 与 Media3 DefaultMediaNotificationProvider 默认渠道一致（复用，不产生多余渠道）。 */
        const val CHANNEL_ID = "default_channel_id"
        /** 与 Media3 MediaNotificationManager 默认通知 id 一致（同 id 替换）。 */
        const val NOTIF_ID = 1001
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }
}
