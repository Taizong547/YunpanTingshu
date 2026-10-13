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
import com.yunpan.tingshu.player.session.SessionKv
import com.yunpan.tingshu.player.session.SessionStore
import kotlinx.coroutines.test.runTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VolumeGainEngineTest {

    private val clock = VirtualClock()
    private val loudness = FakeLoudnessController()
    private val savedGains = mutableListOf<Int>()
    private val gainStore = object : VolumeGainStore {
        override suspend fun load(): Int = 6
        override suspend fun save(db: Int) { savedGains.add(db) }
    }

    private val historyDao = object : HistoryDao {
        override suspend fun upsert(item: HistoryEntity) {}
        override suspend fun allByRecent() = emptyList<HistoryEntity>()
        override suspend fun findByFileId(fileId: String): HistoryEntity? = null
        override suspend fun deleteByFileId(fileId: String) {}
        override suspend fun clear() {}
        override suspend fun setFinished(fileId: String, finished: Boolean) {}
        override suspend fun count() = 0
    }
    private val listenDao = object : ListenDao {
        override suspend fun upsert(item: ListenDayEntity) {}
        override suspend fun msFor(dayStart: Long) = 0L
        override suspend fun todayMs(dayStart: Long) = 0L
        override suspend fun totalMs() = 0L
        override suspend fun all() = emptyList<ListenDayEntity>()
    }
    private val progressStore = object : ProgressStore {
        override suspend fun save(fileId: String, positionMs: Long, durationMs: Long) {}
        override suspend fun get(fileId: String): ProgressStore.PlaybackProgress? = null
    }
    private val resolver = object : PlaybackResolver {
        override suspend fun resolve(file: CloudFile) =
            MediaSpec("https://cdn/${file.id}.mp3", emptyMap(), isHls = false)
    }
    private val sessionKv = object : SessionKv {
        override suspend fun get(): String? = null
        override suspend fun set(json: String?) {}
    }
    private val stateSink = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState())

    private val engine = PlaybackEngine(
        context = ApplicationProvider.getApplicationContext(),
        okHttp = okhttp3.OkHttpClient(),
        resolver = resolver,
        progressStore = progressStore,
        historyDao = historyDao,
        listenDao = listenDao,
        accountProvider = { "acc1" },
        stateSink = stateSink,
        onTimerFired = {},
        sessionStore = SessionStore(sessionKv, clock),
        lockSet = FakeLockSet(),
        focus = FakeAudioFocusController(),
        recoverer = PlaybackRecoverer(clock),
        loudness = loudness,
        gainStore = gainStore,
        clock = clock
    )

    @After fun tearDown() { engine.release() }

    @Test fun setGainPropagatesToControllerAndPersists() = runTest {
        engine.setVolumeGain(6)
        assertThat(loudness.lastGainMB).isEqualTo(600)
        assertThat(savedGains).contains(6)
        assertThat(stateSink.value.volumeGainDb).isEqualTo(6)
    }

    @Test fun cycleAdvancesStage() = runTest {
        engine.setVolumeGain(3)
        engine.cycleVolumeGain()
        assertThat(loudness.lastGainMB).isEqualTo(600) // 3→6
    }

    @Test fun releaseReleasesLoudness() {
        engine.release()
        assertThat(loudness.releaseCount).isAtLeast(1)
    }
}
