package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放历史（SPEC §10.2）。以 fileId 为主键，既是历史也是播放进度的真相源。
 *
 * v1.3（version=3）新增 SHARE 重定位坐标三列：
 * - [shareLinkId]：分享 linkId（冷启动/历史点续播时重新 openShare 用）；
 * - [sharePasswd]：分享提取码；
 * - [shareParentToken]：该音频所在目录的 pCaID（重新列目录定位 presentURL 用）。
 * CLOUD139 记录三列均为 null。
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val fileId: String,
    val accountId: String,
    val source: String,          // FileSource.name
    val folderId: String,
    val folderName: String,      // 专辑/相对目录
    val fileName: String,
    val audioExt: String?,
    val thumbnailUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,      // epoch ms
    val finished: Boolean,
    val shareLinkId: String? = null,
    val sharePasswd: String? = null,
    val shareParentToken: String? = null
)
