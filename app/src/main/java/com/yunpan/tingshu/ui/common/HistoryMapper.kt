package com.yunpan.tingshu.ui.common

import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource

/**
 * 历史记录 → 断点续播 CloudFile 映射（纯 JVM，可单测）。
 *
 * CLOUD139：不带直链，播放时由 PlaybackResolver 用 active 账号取直链。
 * SHARE：保留 source=SHARE；直链是否过期由运行时判断（见 ViewModel），这里只做结构还原。
 */
object HistoryMapper {

    fun toResumeFile(h: HistoryEntity): CloudFile {
        val source = runCatching { FileSource.valueOf(h.source) }.getOrDefault(FileSource.CLOUD139)
        return CloudFile(
            id = h.fileId,
            name = h.fileName,
            isDir = false,
            size = 0,
            updatedAtMs = h.lastPlayedAt,
            thumbnailUrl = h.thumbnailUrl,
            parentId = h.folderId,
            audioExt = h.audioExt,
            source = source,
            isHls = source == FileSource.SHARE,
            durationSec = h.durationMs / 1000,
            albumPath = listOfNotNull(h.folderName.ifEmpty { null }),
            parentToken = h.shareParentToken ?: (if (source == FileSource.SHARE) h.folderId else null),
            shareLinkId = h.shareLinkId,
            sharePasswd = h.sharePasswd
        )
    }

    /** 进度百分比 0..100；总时长未知返回 0。 */
    fun progressPercent(h: HistoryEntity): Int {
        if (h.durationMs <= 0) return 0
        val pct = (h.positionMs * 100.0 / h.durationMs).toInt()
        return pct.coerceIn(0, 100)
    }
}
