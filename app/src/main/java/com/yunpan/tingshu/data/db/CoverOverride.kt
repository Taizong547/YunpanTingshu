package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 封面覆盖映射（COVER_ART_SPEC §2.2）：scope/key → coverId。批量设置只写映射行，不复制图片。
 * scope=FILE 时 targetKey=fileId/contentID；scope=ALBUM 时 targetKey=文件夹 token/albumPath。
 */
@Entity(
    tableName = "cover_override",
    indices = [
        Index(value = ["scope", "target_key"], unique = true),
        Index(value = ["cover_id"])
    ],
    foreignKeys = [ForeignKey(
        entity = Cover::class,
        parentColumns = ["coverId"],
        childColumns = ["cover_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CoverOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    @androidx.room.ColumnInfo(name = "target_key") val targetKey: String,
    @androidx.room.ColumnInfo(name = "cover_id") val coverId: String,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long
)
