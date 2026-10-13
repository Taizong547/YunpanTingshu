package com.yunpan.tingshu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(pl: PlaylistEntity): Long

    @Query("SELECT * FROM playlist ORDER BY createdAt DESC")
    suspend fun allPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :id LIMIT 1")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    /** 把书架条目加入歌单（冲突忽略=已存在则不重复）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_item WHERE playlistId = :playlistId AND bookshelfId = :bookshelfId")
    suspend fun removeItem(playlistId: Long, bookshelfId: Long)

    /** 某歌单下所有书架条目 id。 */
    @Query("SELECT bookshelfId FROM playlist_item WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    suspend fun itemBookIds(playlistId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM playlist_item WHERE playlistId = :playlistId")
    suspend fun itemCount(playlistId: Long): Int
}
