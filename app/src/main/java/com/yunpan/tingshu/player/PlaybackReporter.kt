package com.yunpan.tingshu.player

import com.yunpan.tingshu.data.ProgressStore
import com.yunpan.tingshu.data.db.HistoryDao
import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.data.db.ListenDao
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.util.Clock
import com.yunpan.tingshu.util.SystemClock

/** 听书分析窄接口（与 listen_day 同节奏增量；测试用 fake）。 */
fun interface AlbumAnalyticsSink {
    suspend fun addAlbumPlayed(
        accountId: String,
        albumPath: String,
        deltaMs: Long,
        deltaEpisodes: Int,
        deltaCompleted: Int
    )
}

/**
 * 播放历史/时长上报（纯 Kotlin，便于 fake DAO 单测）。
 *
 *  - [onPlay]：开播 upsert history；记录分析上下文(accountId/albumPath)。
 *  - [onElapsedPlaying]：仅在“真实播放态”由服务层 ~1s tick 调用，按 Asia/Shanghai 日界累加到 listen_day，
 *    并同口径写入 album_day（时长增量）。暂停/缓冲不调用即不累加。
 *  - [onEpisodeConsumed]：切歌(手动下一首)或自然播完切下一首时计 episode+1。
 *  - [onProgress]：周期/暂停/切歌/释放时落进度；position >= duration*0.95 置 finished=true 且本集只计一次 completed+1。
 */
class PlaybackReporter(
    private val historyDao: HistoryDao,
    private val listenDao: ListenDao,
    private val progressStore: ProgressStore,
    private val clock: Clock = SystemClock,
    private val analytics: AlbumAnalyticsSink? = null
) {
    private var currentFile: CloudFile? = null
    private var currentAccountId: String = ""
    private var currentAlbumPath: String = ""
    private var completedCountedFor: String? = null

    /** 开播（含切歌后进入新一集）。[resumePosition] = 断点续播位置。 */
    suspend fun onPlay(file: CloudFile, accountId: String, resumePosition: Long) {
        currentFile = file
        currentAccountId = accountId
        currentAlbumPath = file.albumPath.joinToString("/").ifEmpty {
            file.albumPath.lastOrNull() ?: file.parentId.ifEmpty { file.name }
        }
        completedCountedFor = null
        val existing = historyDao.findByFileId(file.id)
        val entity = HistoryEntity(
            fileId = file.id,
            accountId = accountId,
            source = file.source.name,
            folderId = file.parentId,
            folderName = file.albumPath.lastOrNull() ?: file.parentId,
            fileName = file.name,
            audioExt = file.audioExt,
            thumbnailUrl = file.thumbnailUrl,
            positionMs = resumePosition,
            durationMs = existing?.durationMs ?: 0L,
            lastPlayedAt = clock.nowMs(),
            finished = existing?.finished ?: false,
            shareLinkId = file.shareLinkId,
            sharePasswd = file.sharePasswd,
            shareParentToken = file.parentToken
        )
        historyDao.upsert(entity)
    }

    /** 真实播放增量（仅 isPlaying 时调用）。listen_day + album_day 同时累加。 */
    suspend fun onElapsedPlaying(deltaMs: Long) {
        if (deltaMs <= 0) return
        listenDao.addPlayed(dayStartMs(clock.nowMs()), deltaMs)
        analytics?.addAlbumPlayed(currentAccountId, currentAlbumPath, deltaMs, 0, 0)
    }

    /** 消耗一集（手动下一首 / 自然播完自动切下一首）。 */
    suspend fun onEpisodeConsumed() {
        analytics?.addAlbumPlayed(currentAccountId, currentAlbumPath, 0, 1, 0)
    }

    /** 落进度；接近结尾(>=95%)置完成，且本集只计一次 completed。 */
    suspend fun onProgress(fileId: String, positionMs: Long, durationMs: Long) {
        progressStore.save(fileId, positionMs, durationMs)
        if (durationMs > 0 && positionMs >= durationMs * FINISHED_RATIO) {
            historyDao.setFinished(fileId, true)
            if (completedCountedFor != fileId) {
                completedCountedFor = fileId
                analytics?.addAlbumPlayed(currentAccountId, currentAlbumPath, 0, 0, 1)
            }
        }
    }

    companion object {
        const val FINISHED_RATIO = 0.95

        /** Asia/Shanghai(UTC+8) 当日 0 点对应的 epoch ms。跨日自然拆到新 key。 */
        fun dayStartMs(nowMs: Long): Long {
            val offset = 8L * 3600_000L
            val shifted = nowMs + offset
            val day = shifted / 86_400_000L   // 整数除法向下取整（正数 epoch 安全）
            return day * 86_400_000L - offset
        }
    }
}
