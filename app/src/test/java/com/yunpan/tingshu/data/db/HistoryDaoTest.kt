package com.yunpan.tingshu.data.db

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: HistoryDao

    @Before fun setup() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.historyDao()
    }

    @After fun teardown() { db.close() }

    private fun item(fileId: String, lastPlayedAt: Long, finished: Boolean = false) = HistoryEntity(
        fileId = fileId, accountId = "acc1", source = "CLOUD139", folderId = "f1", folderName = "专辑",
        fileName = fileId, audioExt = "mp3", thumbnailUrl = "", positionMs = 1000, durationMs = 10000,
        lastPlayedAt = lastPlayedAt, finished = finished
    )

    @Test fun upsertDedupUpdates() = runTest {
        dao.upsert(item("a", 100))
        dao.upsert(item("a", 200)) // 同 fileId 更新
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.findByFileId("a")!!.lastPlayedAt).isEqualTo(200)
    }

    @Test fun ordersByRecent() = runTest {
        dao.upsert(item("a", 100))
        dao.upsert(item("b", 300))
        dao.upsert(item("c", 200))
        val list = dao.allByRecent()
        assertThat(list.map { it.fileId }).containsExactly("b", "c", "a").inOrder()
    }

    @Test fun deleteAndClear() = runTest {
        dao.upsert(item("a", 100))
        dao.upsert(item("b", 200))
        dao.deleteByFileId("a")
        assertThat(dao.count()).isEqualTo(1)
        dao.clear()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test fun markFinished() = runTest {
        dao.upsert(item("a", 100, finished = false))
        dao.setFinished("a", true)
        assertThat(dao.findByFileId("a")!!.finished).isTrue()
    }
}
