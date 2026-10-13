package com.yunpan.tingshu.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.db.AlbumDayDao
import com.yunpan.tingshu.data.db.AppDatabase
import com.yunpan.tingshu.util.Clock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AnalyticsTest {

    // 固定时钟：2026-10-04（周日）本地日
    private val fixedNow = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = java.util.TimeZone.getDefault()
    }.parse("2026-10-04")!!.time

    private class FixedClock(val ms: Long) : Clock {
        override fun nowMs() = ms
    }

    private lateinit var db: AppDatabase
    private lateinit var dao: AlbumDayDao
    private lateinit var repo: AnalyticsRepository
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun daysAgo(n: Int): String {
        val c = Calendar.getInstance()
        c.timeInMillis = fixedNow
        c.add(Calendar.DAY_OF_YEAR, -n)
        return fmt.format(c.time)
    }

    @Before fun setup() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.albumDayDao()
        repo = AnalyticsRepository(dao, FixedClock(fixedNow))
    }

    @After fun teardown() { db.close() }

    @Test fun `1 same-day accumulation adds up ms and episodes`() = runTest {
        val today = daysAgo(0)
        dao.addPlayTime(today, "acc1", "专辑A", 300_000, 1, 0)   // 5min
        dao.addPlayTime(today, "acc1", "专辑A", 300_000, 1, 0)
        val rows = dao.getDailyDuration(today, today, "acc1")
        assertThat(rows.single().totalMs).isEqualTo(600_000)
    }

    @Test fun `2 album ranking sorted by ms desc`() = runTest {
        val start = daysAgo(30)
        dao.addPlayTime(daysAgo(0), "acc1", "短", 5 * 3_600_000, 1, 0)
        dao.addPlayTime(daysAgo(0), "acc1", "长", 25 * 3_600_000, 1, 0)
        dao.addPlayTime(daysAgo(0), "acc1", "中", 10 * 3_600_000, 1, 0)
        val top = dao.getTopAlbums(start, "acc1", 10)
        assertThat(top.map { it.albumPath }).containsExactly("长", "中", "短").inOrder()
    }

    @Test fun `3 last 7 days zero-fills missing days`() = runTest {
        // 只在第 2 天前有数据
        dao.addPlayTime(daysAgo(2), "acc1", "专辑A", 1000, 1, 0)
        val series = repo.dailySeries("acc1", 7)
        assertThat(series).hasSize(7)
        // 其余 6 天补 0
        assertThat(series.count { it.playedMs == 0L }).isEqualTo(6)
        assertThat(series.single { it.playedMs > 0 }.date).isEqualTo(daysAgo(2))
        // 升序，最后一天是今天
        assertThat(series.last().date).isEqualTo(daysAgo(0))
    }

    @Test fun `4 completion rate`() = runTest {
        dao.addPlayTime(daysAgo(0), "acc1", "专辑A", 1000, 10, 5)
        val c = repo.completion("acc1", 7)
        assertThat(c.completedEpisodes).isEqualTo(5)
        assertThat(c.totalEpisodes).isEqualTo(10)
        assertThat(c.rate).isEqualTo(0.5f)
    }

    @Test fun `5 accounts isolated`() = runTest {
        val today = daysAgo(0)
        dao.addPlayTime(today, "accA", "专辑A", 1000, 1, 0)
        dao.addPlayTime(today, "accB", "专辑B", 9999, 1, 0)
        assertThat(dao.getDailyDuration(today, today, "accA").single().totalMs).isEqualTo(1000)
        assertThat(dao.getTopAlbums(daysAgo(30), "accA", 10).single().albumPath).isEqualTo("专辑A")
    }

    @Test fun `6 repeated listens same day accumulate`() = runTest {
        val today = daysAgo(0)
        repeat(3) { dao.addPlayTime(today, "acc1", "专辑A", 600_000, 1, 0) } // 3x10min
        assertThat(dao.getDailyDuration(today, today, "acc1").single().totalMs).isEqualTo(1_800_000)
    }

    @Test fun `7 range filter excludes old data`() = runTest {
        dao.addPlayTime(daysAgo(0), "acc1", "A", 1000, 1, 0)
        dao.addPlayTime(daysAgo(10), "acc1", "B", 2000, 1, 0)  // 在 7 天外
        dao.addPlayTime(daysAgo(20), "acc1", "C", 3000, 1, 0)
        val series = repo.dailySeries("acc1", 7)
        assertThat(series.sumOf { it.playedMs }).isEqualTo(1000)
    }

    @Test fun `8 top N limit`() = runTest {
        val start = daysAgo(30)
        for (i in 1..20) dao.addPlayTime(daysAgo(0), "acc1", "album$i", i * 1000L, 1, 0)
        val top = dao.getTopAlbums(start, "acc1", 10)
        assertThat(top).hasSize(10)
        assertThat(top.first().albumPath).isEqualTo("album20")
    }

    @Test fun `9 empty data yields empty series and zero overview`() = runTest {
        val series = repo.dailySeries("acc1", 7)
        assertThat(series).hasSize(7)
        assertThat(series.all { it.playedMs == 0L }).isTrue()
        assertThat(dao.getTopAlbums(daysAgo(30), "acc1", 10)).isEmpty()
        val ov = repo.overview("acc1")
        assertThat(ov.totalMs).isEqualTo(0)
        assertThat(ov.todayMs).isEqualTo(0)
    }
}
