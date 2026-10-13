package com.yunpan.tingshu.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: ProgressStore

    @Before fun setup() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        store = RoomProgressStore(db.historyDao())
    }

    @After fun teardown() { db.close() }

    @Test fun saveAndGetRoundTrip() = runTest {
        store.save("file-1", 12_345, 60_000)
        val p = store.get("file-1")
        assertThat(p).isNotNull()
        assertThat(p!!.positionMs).isEqualTo(12_345)
        assertThat(p.durationMs).isEqualTo(60_000)
    }

    @Test fun missingReturnsNull() = runTest {
        assertThat(store.get("nope")).isNull()
    }

    @Test fun saveOverwritesPosition() = runTest {
        store.save("file-1", 1_000, 60_000)
        store.save("file-1", 2_000, 60_000)
        assertThat(store.get("file-1")!!.positionMs).isEqualTo(2_000)
    }
}
