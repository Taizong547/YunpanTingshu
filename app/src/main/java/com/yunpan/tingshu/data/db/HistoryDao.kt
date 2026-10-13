package com.yunpan.tingshu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity)

    /** 按 lastPlayedAt 倒序。 */
    @Query("SELECT * FROM history ORDER BY lastPlayedAt DESC")
    suspend fun allByRecent(): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE fileId = :fileId LIMIT 1")
    suspend fun findByFileId(fileId: String): HistoryEntity?

    @Query("DELETE FROM history WHERE fileId = :fileId")
    suspend fun deleteByFileId(fileId: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("UPDATE history SET finished = :finished WHERE fileId = :fileId")
    suspend fun setFinished(fileId: String, finished: Boolean)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}
