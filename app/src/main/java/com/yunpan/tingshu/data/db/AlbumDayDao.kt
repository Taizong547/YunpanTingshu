package com.yunpan.tingshu.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 每日一行聚合（柱状图用）。 */
data class DailyDuration(val date: String, val totalMs: Long)

/** 专辑排行一行。 */
data class AlbumRank(val albumPath: String, val totalMs: Long, val totalEpisodes: Int)

/** 完播统计。 */
data class CompletionStats(val totalCompleted: Long?, val totalEpisodes: Long?)

@Dao
interface AlbumDayDao {

    /** 增量累加播放时长/集数/完播（ON CONFLICT(date,accountId,albumPath)）。 */
    @Query(
        """
        INSERT INTO album_day (date, accountId, albumPath, playedMs, episodeCount, completedCount)
        VALUES (:date, :accountId, :albumPath, :deltaMs, :deltaEpisodes, :deltaCompleted)
        ON CONFLICT(`date`, `accountId`, `albumPath`) DO UPDATE SET
            playedMs = playedMs + :deltaMs,
            episodeCount = episodeCount + :deltaEpisodes,
            completedCount = completedCount + :deltaCompleted
        """
    )
    suspend fun addPlayTime(
        date: String,
        accountId: String,
        albumPath: String,
        deltaMs: Long,
        deltaEpisodes: Int,
        deltaCompleted: Int
    )

    /** 近 N 天每日总时长（柱状图），按日期升序。 */
    @Query(
        """
        SELECT date, COALESCE(SUM(playedMs), 0) AS totalMs
        FROM album_day
        WHERE date >= :startDate AND date <= :endDate AND accountId = :accountId
        GROUP BY date
        ORDER BY date ASC
        """
    )
    suspend fun getDailyDuration(startDate: String, endDate: String, accountId: String): List<DailyDuration>

    /** 专辑排行（累计时长倒序，Top N）。 */
    @Query(
        """
        SELECT albumPath, COALESCE(SUM(playedMs), 0) AS totalMs, COALESCE(SUM(episodeCount), 0) AS totalEpisodes
        FROM album_day
        WHERE date >= :startDate AND accountId = :accountId
        GROUP BY albumPath
        ORDER BY totalMs DESC
        LIMIT :limit
        """
    )
    suspend fun getTopAlbums(startDate: String, accountId: String, limit: Int = 10): List<AlbumRank>

    /** 完播/总集数统计。 */
    @Query(
        """
        SELECT COALESCE(SUM(completedCount), 0) AS totalCompleted,
               COALESCE(SUM(episodeCount), 0) AS totalEpisodes
        FROM album_day
        WHERE date >= :startDate AND accountId = :accountId
        """
    )
    suspend fun getCompletionStats(startDate: String, accountId: String): CompletionStats

    /** 全部历史总时长。 */
    @Query("SELECT COALESCE(SUM(playedMs), 0) FROM album_day WHERE accountId = :accountId")
    suspend fun totalMs(accountId: String): Long
}
