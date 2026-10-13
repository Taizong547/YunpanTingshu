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

class PlaybackReporterTest {

    private val clock = VirtualClock().apply { ms = 1_700_000_000_000L } // 任意基准

    // ---- fakes ----
    private val history = LinkedHashMap<String, HistoryEntity>()
    private val historyDao = object : HistoryDao {
        override suspend fun upsert(item: HistoryEntity) { history[item.fileId] = item }
        override suspend fun allByRecent(): List<HistoryEntity> =
            history.values.sortedByDescending { it.lastPlayedAt }
        override suspend fun findByFileId(fileId: String): HistoryEntity? = history[fileId]
        override suspend fun deleteByFileId(fileId: String) { history.remove(fileId) }
        override suspend fun clear() { history.clear() }
        override suspend fun setFinished(fileId: String, finished: Boolean) {
            history[fileId]?.let { history[fileId] = it.copy(finished = finished) }
        }
        override suspend fun count(): Int = history.size
    }

    private val listenDays = sortedMapOf<Long, Long>()
    private val listenDao = object : ListenDao {
        override suspend fun upsert(item: ListenDayEntity) { listenDays[item.dayStartMs] = item.ms }
        override suspend fun msFor(dayStart: Long): Long? = listenDays[dayStart]
        override suspend fun todayMs(dayStart: Long): Long = listenDays[dayStart] ?: 0L
        override suspend fun totalMs(): Long = listenDays.values.sum()
        override suspend fun all(): List<ListenDayEntity> = listenDays.map { ListenDayEntity(it.key, it.value) }
        // addPlayed 使用接口默认实现（依赖上面 upsert/msFor）
    }

    private val savedProgress = mutableMapOf<String, ProgressStore.PlaybackProgress>()
    private val progressStore = object : ProgressStore {
        override suspend fun save(fileId: String, positionMs: Long, durationMs: Long) {
            savedProgress[fileId] = ProgressStore.PlaybackProgress(positionMs, durationMs, System.currentTimeMillis())
        }
        override suspend fun get(fileId: String): ProgressStore.PlaybackProgress? = savedProgress[fileId]
    }

    private val reporter = PlaybackReporter(historyDao, listenDao, progressStore, clock)

    private fun file(id: String, name: String = "$id.mp3", albumPath: List<String> = emptyList()) = CloudFile(
        id = id, name = name, isDir = false, size = 0, updatedAtMs = 0, thumbnailUrl = "thumb",
        parentId = "parent-$id", audioExt = "mp3", source = FileSource.CLOUD139, albumPath = albumPath
    )

    @Test fun onPlayUpsertsHistory() = runTest {
        reporter.onPlay(file("f1", albumPath = listOf("专辑X")), "acc1", 5_000)
        val e = historyDao.findByFileId("f1")!!
        assertThat(e.source).isEqualTo("CLOUD139")
        assertThat(e.folderName).isEqualTo("专辑X")
        assertThat(e.fileName).isEqualTo("f1.mp3")
        assertThat(e.audioExt).isEqualTo("mp3")
        assertThat(e.thumbnailUrl).isEqualTo("thumb")
        assertThat(e.positionMs).isEqualTo(5_000)
        assertThat(e.finished).isFalse()
    }

    @Test fun onPlayWithoutAlbumPathFallsBackToParent() = runTest {
        reporter.onPlay(file("f2"), "acc1", 0)
        assertThat(historyDao.findByFileId("f2")!!.folderName).isEqualTo("parent-f2")
    }

    @Test fun elapsedAccumulatesOnlyWhenCalled() = runTest {
        reporter.onElapsedPlaying(1_000)
        reporter.onElapsedPlaying(1_000)
        // 暂停/缓冲不调用 -> 不累加
        reporter.onElapsedPlaying(500)
        val day = PlaybackReporter.dayStartMs(clock.ms)
        assertThat(listenDao.todayMs(day)).isEqualTo(2_500)
    }

    @Test fun crossDaySplits() = runTest {
        // 基准日
        val day1 = PlaybackReporter.dayStartMs(clock.ms)
        reporter.onElapsedPlaying(1_000)
        // 跨到第二天
        clock.advance(25L * 3600_000L)
        val day2 = PlaybackReporter.dayStartMs(clock.ms)
        assertThat(day2).isNotEqualTo(day1)
        reporter.onElapsedPlaying(2_000)
        assertThat(listenDao.todayMs(day1)).isEqualTo(1_000)
        assertThat(listenDao.todayMs(day2)).isEqualTo(2_000)
    }

    @Test fun upsertSameFileIdUpdatesNotDuplicate() = runTest {
        reporter.onPlay(file("f9"), "acc1", 0)
        clock.advance(1_000)
        reporter.onPlay(file("f9"), "acc1", 3_000)
        assertThat(historyDao.count()).isEqualTo(1)
        assertThat(historyDao.findByFileId("f9")!!.positionMs).isEqualTo(3_000)
    }

    @Test fun ninetyFivePercentMarksFinished() = runTest {
        reporter.onPlay(file("f10"), "acc1", 0)
        reporter.onProgress("f10", 9_000, 10_000) // 90%
        assertThat(historyDao.findByFileId("f10")!!.finished).isFalse()
        reporter.onProgress("f10", 9_600, 10_000) // 96%
        assertThat(historyDao.findByFileId("f10")!!.finished).isTrue()
    }

    @Test fun progressSaved() = runTest {
        reporter.onProgress("f11", 12_345, 60_000)
        assertThat(progressStore.get("f11")!!.positionMs).isEqualTo(12_345)
    }
}
