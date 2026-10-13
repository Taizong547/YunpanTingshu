package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 歌单项：把某个书架条目（一本书/一个文件夹）加入某歌单。
 * (playlistId, bookshelfId) 唯一，避免重复加入。
 */
@Entity(
    tableName = "playlist_item",
    indices = [Index("playlistId"), Index("bookshelfId"), Index(value = ["playlistId", "bookshelfId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val bookshelfId: Long,
    val addedAt: Long
)
