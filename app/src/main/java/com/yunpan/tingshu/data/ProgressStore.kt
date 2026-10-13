package com.yunpan.tingshu.data

import com.yunpan.tingshu.data.db.HistoryDao
import com.yunpan.tingshu.data.db.HistoryEntity

/**
 * 播放进度存储（断点续播）。以 history 表为唯一真相源（SPEC §10.2：不与 history 两套不一致）。
 */
interface ProgressStore {
    suspend fun save(fileId: String, positionMs: Long, durationMs: Long)
    suspend fun get(fileId: String): PlaybackProgress?

    data class PlaybackProgress(
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long
    )
}

class RoomProgressStore(
    private val historyDao: HistoryDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : ProgressStore {

    override suspend fun save(fileId: String, positionMs: Long, durationMs: Long) {
        val existing = historyDao.findByFileId(fileId)
        val item = if (existing != null) {
            existing.copy(positionMs = positionMs, durationMs = durationMs, lastPlayedAt = nowMs())
        } else {
            HistoryEntity(
                fileId = fileId,
                accountId = "",
                source = "",
                folderId = "",
                folderName = "",
                fileName = fileId,
                audioExt = null,
                thumbnailUrl = "",
                positionMs = positionMs,
                durationMs = durationMs,
                lastPlayedAt = nowMs(),
                finished = false
            )
        }
        historyDao.upsert(item)
    }

    override suspend fun get(fileId: String): ProgressStore.PlaybackProgress? {
        val e = historyDao.findByFileId(fileId) ?: return null
        return ProgressStore.PlaybackProgress(e.positionMs, e.durationMs, e.lastPlayedAt)
    }
}
