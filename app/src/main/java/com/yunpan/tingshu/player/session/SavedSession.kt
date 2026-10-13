package com.yunpan.tingshu.player.session

import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.player.PlayMode

/** 会话来源。 */
enum class SessionSource { CLOUD, SHARE }

/**
 * 队列中一项的稳定标识 + 元数据（绝不存会过期的直链 URL）。
 */
data class SessionTrack(
    val fileId: String,
    val childToken: String?,
    val name: String,
    val albumPath: List<String>,
    val audioExt: String?,
    val durationSec: Long,
    val isHls: Boolean,
    val parentToken: String? = null       // SHARE：该曲目所在目录 pCaID（恢复后重定位用）
)

/**
 * 可恢复的播放会话（冷启动续播）。
 */
data class SavedSession(
    val source: SessionSource,
    val accountId: String?,          // CLOUD
    val linkId: String?,              // SHARE
    val passwd: String?,              // SHARE
    val shareName: String?,           // SHARE
    val albumTitle: String,
    val rootToken: String?,
    val tracks: List<SessionTrack>,
    val index: Int,
    val positionMs: Long,
    val playMode: PlayMode,
    val speed: Float,
    val sleepConfig: String?,         // "Minutes:30" / "EndOfEpisode" / "Episodes:2"
    val updatedAt: Long
) {
    fun currentTrack(): SessionTrack? = tracks.getOrNull(index)
}
