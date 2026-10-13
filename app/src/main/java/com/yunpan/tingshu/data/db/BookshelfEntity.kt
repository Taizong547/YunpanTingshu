package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地书单（书架）条目：保存一个"书"（云盘文件夹 / 分享文件夹）的坐标与进度，
 * 点击时按坐标重新解析并播放（v1.3 新增，DB version=3）。
 *
 * 坐标语义（与 CloudFile/BrowseViewModel 一致）：
 * - CLOUD139：[accountId] + [folderId]（云盘文件夹 id），播放时切账号后 scanAudio；
 * - SHARE：[shareLinkId] + [sharePasswd] + [folderId]（pCaID，root 存 "root"），播放时重新 openShare 后列目录/扫描。
 * [albumPath] 用 "/" 分隔的相对路径（仅展示用）。
 */
@Entity(tableName = "bookshelf", indices = [Index("addedAtMs")])
data class BookshelfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,              // FileSource.name
    val accountId: String? = null,   // CLOUD139
    val shareLinkId: String? = null, // SHARE
    val sharePasswd: String? = null, // SHARE
    val folderId: String,            // 文件夹坐标（CLOUD139 folderId / SHARE pCaID）
    val folderName: String,          // 书名（可重命名）
    val albumPath: String = "",      // "/" 分隔相对路径（仅展示）
    val coverId: String? = null,     // 封面 coverId（内容寻址），预留
    val positionMs: Long = 0L,       // 上次播放位置（进度展示/续播参考）
    val totalMs: Long = 0L,
    val addedAtMs: Long,             // epoch ms
    val updatedAtMs: Long            // epoch ms
)
