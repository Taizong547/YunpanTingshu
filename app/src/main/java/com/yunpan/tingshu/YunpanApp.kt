package com.yunpan.tingshu

import android.app.Application
import android.content.pm.ApplicationInfo
import com.yunpan.tingshu.cloud139.Cloud139Api
import com.yunpan.tingshu.cloud139.Cloud139Repository
import com.yunpan.tingshu.cloud139.share.ShareRepository
import com.yunpan.tingshu.data.AccountStore
import com.yunpan.tingshu.data.AnalyticsRepository
import com.yunpan.tingshu.data.DefaultAccountStore
import com.yunpan.tingshu.data.DefaultPlaybackResolver
import com.yunpan.tingshu.data.ProgressStore
import com.yunpan.tingshu.data.RoomProgressStore
import com.yunpan.tingshu.data.SettingsStore
import com.yunpan.tingshu.data.cover.CoverArtProvider
import com.yunpan.tingshu.data.cover.CoverStore
import com.yunpan.tingshu.data.cover.DirCoverStorage
import com.yunpan.tingshu.data.cover.RealAutoCoverSource
import com.yunpan.tingshu.data.cover.RealImageCompressor
import com.yunpan.tingshu.data.db.AppDatabase
import com.yunpan.tingshu.data.db.BookshelfDao
import com.yunpan.tingshu.data.db.HistoryDao
import com.yunpan.tingshu.data.db.ListenDao
import com.yunpan.tingshu.data.model.PlaybackResolver
import com.yunpan.tingshu.player.PlaybackManager
import com.yunpan.tingshu.player.session.DataStoreSessionKv
import com.yunpan.tingshu.player.session.SessionStore
import com.yunpan.tingshu.ui.browse.ShareSession
import com.yunpan.tingshu.util.CrashHandler
import com.yunpan.tingshu.util.SystemClock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * 依赖容器（ServiceLocator）。UI/播放代理统一从 [YunpanApp.locator] 取用，不要自行 new。
 *
 * 保留现有 Application 注册与主题（manifest 未改）。
 */
class ServiceLocator(private val app: Application) {

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            val debuggable =
                (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    val accountStore: AccountStore = DefaultAccountStore.create(app)

    val settingsStore: SettingsStore = SettingsStore.create(app)

    /** 待进入浏览页的分享会话（我的页打开分享链接后设置，浏览页读取并消费）。 */
    val shareSession: ShareSession = ShareSession()

    val database: AppDatabase = AppDatabase.create(app)
    val historyDao: HistoryDao get() = database.historyDao()
    val listenDao: ListenDao get() = database.listenDao()
    val bookshelfDao: BookshelfDao get() = database.bookshelfDao()
    val playlistDao: com.yunpan.tingshu.data.db.PlaylistDao get() = database.playlistDao()

    val progressStore: ProgressStore = RoomProgressStore(historyDao)

    val cloud139Repository: Cloud139Repository = Cloud139Api(okHttp)
    val shareRepository: ShareRepository = ShareRepository(okHttp)

    /** 封面覆盖层（纯本地）：存储根 = getExternalFilesDir(null)/covers/。 */
    val coverStore: CoverStore by lazy {
        val root = app.getExternalFilesDir(null)?.resolve("covers") ?: app.filesDir.resolve("covers")
        CoverStore(database.coverDao(), database.coverOverrideDao(), DirCoverStorage(root), RealImageCompressor)
    }
    val coverArtProvider: CoverArtProvider by lazy {
        CoverArtProvider(
            coverStore,
            RealAutoCoverSource(
                cloudRepo = cloud139Repository,
                shareRepo = shareRepository,
                accountProvider = { accountStore.active() }
            )
        )
    }

    /** 听书分析。 */
    val analyticsRepository: AnalyticsRepository by lazy { AnalyticsRepository(database.albumDayDao()) }

    val playbackResolver: PlaybackResolver =
        DefaultPlaybackResolver(cloud139Repository, shareRepository) { accountStore.active() }

    /** app 级播放控制器（播放引擎/队列/睡眠定时器/历史上报的 UI 入口）。 */
    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(app, SessionStore(DataStoreSessionKv(app), SystemClock), settingsStore)
    }
}

/**
 * 应用入口。装配单例 OkHttp / DataStore / Room / 仓库容器。
 */
class YunpanApp : Application() {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        // 启动时恢复深色/浅色偏好（默认浅色）
        runCatching {
            val dark = getSharedPreferences("ui_prefs", MODE_PRIVATE).getBoolean("dark", false)
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (dark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        super.onCreate()
        // 先装崩溃捕获（任何初始化崩溃都会落盘，便于真机精确定位）
        CrashHandler.install(this)
        locator = ServiceLocator(this)
        instance = this
    }

    companion object {
        lateinit var instance: YunpanApp
            private set
    }
}
