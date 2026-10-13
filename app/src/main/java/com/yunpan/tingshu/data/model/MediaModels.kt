package com.yunpan.tingshu.data.model

/** 文件来源：个人登录态云盘 vs 无登录公开分享。 */
enum class FileSource { CLOUD139, SHARE }

/**
 * 统一文件/文件夹模型（个人云盘与分享共用）。
 *
 * @param id          文件或文件夹在其来源内的 id
 * @param name        显示名（含扩展名，音频即文件名）
 * @param isDir       是否文件夹（文件夹视为专辑/播放列表）
 * @param size        字节
 * @param updatedAtMs 修改时间 epoch ms（解析失败为 0）
 * @param thumbnailUrl 缩略图（可能为空串）
 * @param parentId    父目录 id
 * @param audioExt    音频扩展名（小写、不含点）；非空且在白名单=可播；文件夹为 null
 * @param source      来源
 * @param childToken  打开该文件夹时「请求子目录用的 token」。
 *                    个人云盘 = 文件夹 id；分享 root 首次为字面量 "root"，子文件夹 = 父pCaID+"/"+caID 或 caID。
 * @param directUrl   分享音频直接给 presentURL(m3u8)；个人云盘为 null（播放时再 getPlayUrl）
 * @param isHls       是否 HLS(m3u8)。分享音频恒 true；个人云盘在 resolve 时按 URL/扩展名判定
 * @param durationSec 时长（秒，分享音频来自 extInfo.duration；未知 0）
 * @param albumPath   相对目录分段（递归扫描用，扁平后每条带）
 * @param parentToken 分享重定位用：该分享音频所在目录的 pCaID（列目录定位用）；恢复会话后可能为 null。
 * @param shareLinkId 分享重定位用：分享 linkId（冷启动恢复 directUrl=null 时重新 openShare）；仅 SHARE。
 * @param sharePasswd 分享重定位用：分享提取码；仅 SHARE。
 */
data class CloudFile(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val updatedAtMs: Long,
    val thumbnailUrl: String,
    val parentId: String,
    val audioExt: String?,
    val source: FileSource,
    val childToken: String? = null,
    val directUrl: String? = null,
    val isHls: Boolean = false,
    val durationSec: Long = 0,
    val albumPath: List<String> = emptyList(),
    val parentToken: String? = null,
    val shareLinkId: String? = null,
    val sharePasswd: String? = null
) {
    val isAudio: Boolean get() = audioExt != null && audioExt in AUDIO_WHITELIST

    fun withAlbumPath(path: List<String>): CloudFile = copy(albumPath = path)
}

/** 音频扩展名白名单（不区分大小写）。文件夹视为专辑/播放列表。 */
val AUDIO_WHITELIST = setOf(
    "mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav", "wma", "amr"
)

/** 一页列表结果。nextCursor 为空表示无下一页。 */
data class ListPage(
    val items: List<CloudFile>,
    val nextCursor: String
)

/** 递归扫描深度。CURRENT=仅当前层；LEVEn=向下 n 级；ALL=不限深。 */
enum class ScanDepth {
    CURRENT, LEVEL1, LEVEL2, LEVEL3, LEVEL5, ALL;

    /** 相对 rootFolderId 的最大向下层数（root 自身为第 0 层）。-1 = 不限深。 */
    fun maxDepth(): Int = when (this) {
        CURRENT -> 0
        LEVEL1 -> 1
        LEVEL2 -> 2
        LEVEL3 -> 3
        LEVEL5 -> 5
        ALL -> -1
    }
}

/** 一条扫描到的音频 + 其相对专辑路径。 */
data class ScannedTrack(
    val file: CloudFile,
    val albumPath: List<String>
)

/** 扫描进度回调。 */
data class ScanProgress(
    val visitedFolders: Int,
    val foundTracks: Int,
    val currentFolder: String
)

/** 播放直链规格：URI + 请求头 + 是否 HLS。后续 ExoPlayer 据此选 HlsMediaSource / 默认源。 */
data class MediaSpec(
    val uri: String,
    val headers: Map<String, String>,
    val isHls: Boolean
)
