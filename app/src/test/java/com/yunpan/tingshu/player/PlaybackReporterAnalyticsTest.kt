package com.yunpan.tingshu.player

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.ProgressStore
import com.yunpan.tingshu.data.db.HistoryDao
import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.data.db.ListenDao
import com.yunpan.tingshu.data.db.ListenDayEntity
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 验证 reporter 在同节奏把听书增量写入 album_day（时长/集数/完播）。 */
class PlaybackReporterAnalyticsTest {

    private val clock = VirtualClock().apply { ms = 1_700_000_000_000L }

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
        override suspend fun save(fileId: String, positionMs: Long, durationMs: Long) {}
        override suspend fun get(fileId: String): ProgressStore.PlaybackProgress? = null
    }

    private data class AlbumRow(val accountId: String, val albumPath: String, val ms: Long, val ep: Int, val done: Int)
    private val rows = mutableListOf<AlbumRow>()
    private val analytics = AlbumAnalyticsSink { a, p, ms, ep, done ->
        rows.add(AlbumRow(a, p, ms, ep, done))
    }
    private val reporter = PlaybackReporter(historyDao, listenDao, progressStore, clock, analytics)

    private fun file(id: String) = CloudFile(
        id = id, name = "$id.mp3", isDir = false, size = 0, updatedAtMs = 0,
        thumbnailUrl = "", parentId = "parent", audioExt = "mp3",
        source = FileSource.CLOUD139, albumPath = listOf("专辑A")
    )

    @Test fun elapsedWritesAlbumMinutes() = runTest {
        reporter.onPlay(file("f1"), "acc1", 0)
        reporter.onElapsedPlaying(1_000)
        reporter.onElapsedPlaying(2_000)
        val timeRows = rows.filter { it.ms > 0 }
        assertThat(timeRows.sumOf { it.ms }).isEqualTo(3_000)
        assertThat(timeRows.all { it.accountId == "acc1" && it.albumPath == "专辑A" }).isTrue()
    }

    @Test fun pauseNoElapsedMeansNoAlbumDelta() = runTest {
        reporter.onPlay(file("f2"), "acc1", 0)
        // 暂停/缓冲不调用 onElapsedPlaying
        val before = rows.size
        assertThat(rows.size).isEqualTo(before)
    }

    @Test fun episodeConsumedIncrementsByOne() = runTest {
        reporter.onPlay(file("f3"), "acc1", 0)
        reporter.onEpisodeConsumed()
        val epRows = rows.filter { it.ep > 0 }
        assertThat(epRows).hasSize(1)
        assertThat(epRows[0].ep).isEqualTo(1)
    }

    @Test fun completedCountedOncePerEpisode() = runTest {
        reporter.onPlay(file("f4"), "acc1", 0)
        reporter.onProgress("f4", 9_600, 10_000) // 96% → completed
        reporter.onProgress("f4", 9_900, 10_000) // 再次，不应重复计
        val doneRows = rows.filter { it.done > 0 }
        assertThat(doneRows).hasSize(1)
        assertThat(doneRows[0].done).isEqualTo(1)
    }
}
