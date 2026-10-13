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
class ListenDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ListenDao

    @Before fun setup() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.listenDao()
    }

    @After fun teardown() { db.close() }

    @Test fun sameDayAccumulates() = runTest {
        val day = 1_700_000_000_000L
        dao.addPlayed(day, 5_000)
        dao.addPlayed(day, 7_000)
        assertThat(dao.todayMs(day)).isEqualTo(12_000)
        assertThat(dao.totalMs()).isEqualTo(12_000)
    }

    @Test fun totalsAcrossDays() = runTest {
        dao.addPlayed(1_000, 5_000)
        dao.addPlayed(2_000, 3_000)
        assertThat(dao.totalMs()).isEqualTo(8_000)
        assertThat(dao.todayMs(1_000)).isEqualTo(5_000)
    }
}
