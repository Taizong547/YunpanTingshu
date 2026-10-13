package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 听书分析按天×专辑聚合（ANALYTICS_SPEC §3.2）。
 * 同一 (date, accountId, albumPath) 唯一；播放上报时 SQL 增量累加，避免读-改-写竞态。
 */
@Entity(
    tableName = "album_day",
    indices = [
        Index(value = ["date", "accountId", "albumPath"], unique = true),
        Index(value = ["date"]),
        Index(value = ["albumPath"])
    ]
)
data class AlbumDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyy-MM-dd（本地时区） */
    val date: String,
    val accountId: String,
    /** 专辑/文件夹路径（"爱听哪部书"口径） */
    val albumPath: String,
    /** 当日该专辑累计收听毫秒 */
    val playedMs: Long,
    /** 当日播放过的集数（去重口径累加） */
    val episodeCount: Int,
    /** 当日完播集数（进度≥95%） */
    val completedCount: Int
)
