package com.yunpan.tingshu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ListenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ListenDayEntity)

    @Query("SELECT ms FROM listen_day WHERE dayStartMs = :dayStart LIMIT 1")
    suspend fun msFor(dayStart: Long): Long?

    @Query("SELECT COALESCE(SUM(ms), 0) FROM listen_day WHERE dayStartMs = :dayStart")
    suspend fun todayMs(dayStart: Long): Long

    @Query("SELECT COALESCE(SUM(ms), 0) FROM listen_day")
    suspend fun totalMs(): Long

    @Query("SELECT * FROM listen_day ORDER BY dayStartMs DESC")
    suspend fun all(): List<ListenDayEntity>

    /** 同日累加（播放态 tick 增量，暂停/缓冲不计）。 */
    @Transaction
    suspend fun addPlayed(dayStart: Long, deltaMs: Long) {
        val cur = msFor(dayStart) ?: 0L
        upsert(ListenDayEntity(dayStart, cur + deltaMs))
    }
}
