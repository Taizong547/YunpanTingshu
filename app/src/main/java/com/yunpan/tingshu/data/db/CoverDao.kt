package com.yunpan.tingshu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CoverDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCover(cover: Cover)

    @Query("SELECT * FROM cover WHERE coverId = :coverId LIMIT 1")
    suspend fun getCover(coverId: String): Cover?

    /** 某 coverId 被多少映射引用；为 0 时可删物理文件。 */
    @Query("SELECT COUNT(*) FROM cover_override WHERE cover_id = :coverId")
    suspend fun countRefs(coverId: String): Int

    @Query("DELETE FROM cover WHERE coverId = :coverId")
    suspend fun deleteCover(coverId: String)
}
