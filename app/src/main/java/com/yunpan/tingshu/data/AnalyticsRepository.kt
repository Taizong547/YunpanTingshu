package com.yunpan.tingshu.data

import com.yunpan.tingshu.data.db.AlbumDayDao
import com.yunpan.tingshu.data.db.AlbumRank
import com.yunpan.tingshu.util.Clock
import com.yunpan.tingshu.util.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 一行每日时长（补零后，柱状图用）。 */
data class DayPoint(val date: String, val playedMs: Long)

/** 概览卡片聚合。 */
data class Overview(
    val totalMs: Long,
    val todayMs: Long,
    val last7Ms: Long,
    val last30Ms: Long
)

/** 完播统计（UI 用）。 */
data class Completion(val completedEpisodes: Long, val totalEpisodes: Long) {
    val rate: Float get() = if (totalEpisodes <= 0) 0f else completedEpisodes.toFloat() / totalEpisodes
}

/**
 * 听书分析仓储（ANALYTICS_SPEC §3/§5）。只暴露 DAO/增量方法与纯聚合逻辑；
 * 播放层下一步在同事务调用 listen_day 与 addAlbumPlayed。补零/范围裁剪纯 Kotlin 可测。
 */
class AnalyticsRepository(
    private val dao: AlbumDayDao,
    private val clock: Clock = SystemClock
) {

    /** 播放上报增量：时长 + 集数 + 完播（同事务由调用方保证与 listen_day 原子）。 */
    suspend fun addAlbumPlayed(
        accountId: String,
        albumPath: String,
        deltaMs: Long,
        deltaEpisodes: Int = 0,
        deltaCompleted: Int = 0,
        date: String = todayStr()
    ) {
        if (albumPath.isBlank()) return
        dao.addPlayTime(date, accountId, albumPath, deltaMs, deltaEpisodes, deltaCompleted)
    }

    suspend fun overview(accountId: String): Overview {
        val total = dao.totalMs(accountId)
        val today = dao.getDailyDuration(todayStr(), todayStr(), accountId).sumOf { it.totalMs }
        val last7 = rangeSum(accountId, recentStart(6))
        val last30 = rangeSum(accountId, recentStart(29))
        return Overview(total, today, last7, last30)
    }

    /** 近 N 天每日序列（含今天），无记录的天补 0，按日期升序。 */
    suspend fun dailySeries(accountId: String, days: Int): List<DayPoint> {
        val start = recentStart(days - 1)
        val rows = dao.getDailyDuration(start, todayStr(), accountId).associate { it.date to it.totalMs }
        return dayStrings(days).map { d -> DayPoint(d, rows[d] ?: 0L) }
    }

    suspend fun topAlbums(accountId: String, days: Int, limit: Int = 10): List<AlbumRank> {
        return dao.getTopAlbums(recentStart(days - 1), accountId, limit)
    }

    suspend fun completion(accountId: String, days: Int): Completion {
        val s = dao.getCompletionStats(recentStart(days - 1), accountId)
        return Completion(s.totalCompleted ?: 0, s.totalEpisodes ?: 0)
    }

    private suspend fun rangeSum(accountId: String, startDate: String): Long =
        dao.getDailyDuration(startDate, todayStr(), accountId).sumOf { it.totalMs }

    // ---------- 纯日期工具（包级可见，便于 JVM 测） ----------

    fun todayStr(): String = fmt(clock.nowMs())

    /** n 天前的日期串（n=0 即今天）。 */
    fun recentStart(n: Int): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = clock.nowMs()
        cal.add(Calendar.DAY_OF_YEAR, -n)
        return fmt(cal.timeInMillis)
    }

    /** 最近 n 天（含今天）的日期串，升序。 */
    fun dayStrings(n: Int): List<String> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = clock.nowMs()
        val out = ArrayList<String>(n)
        for (i in n - 1 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, -i)
            out.add(fmt(c.timeInMillis))
        }
        return out
    }

    private fun fmt(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(ms)
}
