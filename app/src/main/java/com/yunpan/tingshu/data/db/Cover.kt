package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 物理封面图片（内容寻址，COVER_ART_SPEC §2.2）。coverId = 压缩后字节 SHA-256 前 16 位，
 * 即文件名 <coverId>.jpg。同一张图全 App 只存一份，多集/多专辑共用。
 */
@Entity(tableName = "cover")
data class Cover(
    @PrimaryKey val coverId: String,
    /** 压缩后文件大小（字节） */
    val fileSize: Long,
    /** GALLERY / CLOUD / AUTO */
    val source: String,
    val createdAt: Long
)
