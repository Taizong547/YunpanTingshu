package com.yunpan.tingshu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookshelfDao {

    /** 加入书架；同坐标（source+folderId）重复收藏时覆盖（REPLACE 语义）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: BookshelfEntity)

    /** 按加入时间倒序。 */
    @Query("SELECT * FROM bookshelf ORDER BY addedAtMs DESC")
    suspend fun all(): List<BookshelfEntity>

    @Query("SELECT * FROM bookshelf WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookshelfEntity?

    @Query("SELECT * FROM bookshelf WHERE source = :source AND folderId = :folderId LIMIT 1")
    suspend fun findByCoordinates(source: String, folderId: String): BookshelfEntity?

    /** 重命名书名。 */
    @Query("UPDATE bookshelf SET folderName = :newName, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun rename(id: Long, newName: String, updatedAtMs: Long)

    /** 更新播放进度（点击播放后回写，供书架展示续播进度）。 */
    @Query("UPDATE bookshelf SET positionMs = :positionMs, totalMs = :totalMs, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun updateProgress(id: Long, positionMs: Long, totalMs: Long, updatedAtMs: Long)

    /** 刷新校验（更新 updatedAtMs，表示最近一次校验/播放）。 */
    @Query("UPDATE bookshelf SET updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun touch(id: Long, updatedAtMs: Long)

    @Query("DELETE FROM bookshelf WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM bookshelf")
    suspend fun count(): Int
}
