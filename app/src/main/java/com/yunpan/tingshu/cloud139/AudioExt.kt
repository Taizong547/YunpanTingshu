package com.yunpan.tingshu.cloud139

/** 音频扩展名白名单（不区分大小写，SPEC §3.7）。文件夹视为专辑/播放列表。 */
object AudioExt {
    val WHITELIST = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav", "wma", "amr",
        // 视频文件也只取音频（本 App 无视频渲染视图，ExoPlayer 自动只播声音）
        "mp4", "mkv", "mov", "webm", "avi", "ts"
    )

    fun isAudio(ext: String?): Boolean = ext != null && ext in WHITELIST

    /** 从文件名取小写扩展名（不含点）；无扩展名返回 null。 */
    fun extOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }
}
