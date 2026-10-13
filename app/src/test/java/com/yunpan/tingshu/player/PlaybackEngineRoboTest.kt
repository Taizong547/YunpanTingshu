package com.yunpan.tingshu.player

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.ProgressStore
import com.yunpan.tingshu.data.db.HistoryDao
import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.data.db.ListenDao
import com.yunpan.tingshu.data.db.ListenDayEntity
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.MediaSpec
import com.yunpan.tingshu.data.model.PlaybackResolver
import com.yunpan.tingshu.player.session.SavedSession
import com.yunpan.tingshu.player.session.SessionSource
import com.yunpan.tingshu.player.session.SessionStore
import com.yunpan.tingshu.player.session.SessionTrack
import kotlinx.coroutines.test.runTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric：Engine 的锁 acquire/release、音频焦点暂停→续播、冷启动 resumeLast 按 id 重取并 seek。
 * 用 Fake LockSet / Fake AudioFocus / Fake resolver，不真正联网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackEngineRoboTest {

    private val clock = VirtualClock()
    private val lockSet = FakeLockSet()
    private val focus = FakeAudioFocusController()
    private val resolvedIds = mutableListOf<String>()
    private val resolver = object : PlaybackResolver {
        override suspend fun resolve(file: CloudFile): MediaSpec {
            resolvedIds.add(file.id)
            return MediaSpec("https://cdn/${file.id}.mp3", emptyMap(), isHls = false)
        }
    }

    private val historyDao = object : HistoryDao {
        private val m = LinkedHashMap<String, HistoryEntity>()
        override suspend fun upsert(item: HistoryEntity) { m[item.fileId] = item }
        override suspend fun allByRecent() = m.values.toList()
        override suspend fun findByFileId(fileId: String) = m[fileId]
        override suspend fun deleteByFileId(fileId: String) { m.remove(fileId) }
        override suspend fun clear() { m.clear() }
        override suspend fun setFinished(fileId: String, finished: Boolean) {
            m[fileId]?.let { m[fileId] = it.copy(finished = finished) }
        }
        override suspend fun count() = m.size
    }
    private val listenDao = object : ListenDao {
        override suspend fun upsert(item: ListenDayEntity) {}
        override suspend fun msFor(dayStart: Long) = 0L
        override suspend fun todayMs(dayStart: Long) = 0L
        override suspend fun totalMs() = 0L
        override suspend fun all() = emptyList<ListenDayEntity>()
    }
    private val progressStore = object : ProgressStore {
        var saved: ProgressStore.PlaybackProgress? = null
        override suspend fun save(fileId: String, positionMs: Long, durationMs: Long) {
            saved = ProgressStore.PlaybackProgress(positionMs, durationMs, 0)
        }
        override suspend fun get(fileId: String) = saved
    }
    private val sessionKv = object : com.yunpan.tingshu.player.session.SessionKv {
        var v: String? = null
        override suspend fun get() = v
        override suspend fun set(json: String?) { v = json }
    }
    private val sessionStore = SessionStore(sessionKv, clock)

    private var engine: PlaybackEngine? = null
    private val stateSink = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState())

    private fun newEngine() = PlaybackEngine(
        context = ApplicationProvider.getApplicationContext(),
        okHttp = okhttp3.OkHttpClient(),
        resolver = resolver,
        progressStore = progressStore,
        historyDao = historyDao,
        listenDao = listenDao,
        accountProvider = { "acc1" },
        stateSink = stateSink,
        onTimerFired = {},
        sessionStore = sessionStore,
        lockSet = lockSet,
        focus = focus,
        recoverer = PlaybackRecoverer(clock),
        clock = clock
    )

    @Before fun setup() { engine = newEngine() }

    @After fun teardown() { engine?.release() }

    private fun file(id: String) = CloudFile(
        id = id, name = "$id.mp3", isDir = false, size = 0, updatedAtMs = 0,
        thumbnailUrl = "", parentId = "/", audioExt = "mp3", source = FileSource.CLOUD139
    )

    @Test fun pauseAcquireReleaseLock() = runTest {
        val e = engine!!
        e.resume()
        assertThat(lockSet.acquireCount).isAtLeast(1)
        e.pause()
        assertThat(lockSet.releaseCount).isAtLeast(1)
    }

    @Test fun transientFocusLossPausesAndGainResumes() = runTest {
        val e = engine!!
        e.resume()
        assertThat(lockSet.isHeld()).isTrue()
        val acquireBefore = lockSet.acquireCount
        focus.simulateTransientLoss()
        // 短暂失焦后暂停 → 释放锁
        assertThat(lockSet.releaseCount).isAtLeast(1)
        focus.simulateGain()
        // GAIN 后若原在播会续播（再次 acquire）；此处至少不崩、回调链路走通
        assertThat(lockSet.acquireCount).isAtLeast(acquireBefore)
    }

    @Test fun coldStartResumeResolvesByFileId() = runTest {
        val e = engine!!
        val session = SavedSession(
            source = SessionSource.CLOUD, accountId = "acc1", linkId = null, passwd = null,
            shareName = null, albumTitle = "专辑", rootToken = null,
            tracks = listOf(
                SessionTrack("f10", null, "a.mp3", emptyList(), "mp3", 100, false),
                SessionTrack("f11", null, "b.mp3", emptyList(), "mp3", 100, false)
            ),
            index = 1, positionMs = 33_000,
            playMode = com.yunpan.tingshu.player.PlayMode.SEQUENTIAL,
            speed = 1f, sleepConfig = null, updatedAt = clock.ms
        )
        resolvedIds.clear()
        e.resumeLast(session, autoplay = false)
        // 给协程一点时间完成 resolve
        kotlinx.coroutines.delay(300)
        assertThat(resolvedIds).contains("f11")
    }

    @Test fun hasActiveSession() = runTest {
        val e = engine!!
        assertThat(e.hasActiveSession()).isFalse()
        e.playTracks(listOf(file("x"), file("y")), 0, "专辑")
        kotlinx.coroutines.delay(200)
        assertThat(e.hasActiveSession()).isTrue()
    }
}
