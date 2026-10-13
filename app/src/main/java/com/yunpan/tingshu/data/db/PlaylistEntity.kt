package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 命名歌单（DB v5）：用户自建的收藏集合，如"言情小说""历史评书"。
 * 歌单项通过 [PlaylistItemEntity] 引用书架条目（[BookshelfEntity.id]）。
 */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)
