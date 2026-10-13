package com.yunpan.tingshu.ui.common

import com.yunpan.tingshu.data.cover.CoverStore

/** 封面作用范围（用户在弹层选择）。 */
enum class CoverScope { THIS_FILE, THIS_ALBUM, QUEUE }

/** 作用范围 → coverStore 写入目标 key 列表（纯逻辑，JVM 可测）。 */
object CoverScopeKeys {

    /**
     * @param fileId 当前文件 id（THIS_FILE 用）
     * @param albumKey 专辑 key（parentToken ?: parentId，THIS_ALBUM 用）
     * @param queueAlbumKeys 当前队列里每个文件的 albumKey（QUEUE 用）
     * @return (scope, targetKeys) 对，供 setFileCover / setAlbumCover / batchSet 使用
     */
    fun resolve(
        scope: CoverScope,
        fileId: String,
        albumKey: String,
        queueAlbumKeys: List<String>
    ): Pair<String, List<String>> = when (scope) {
        CoverScope.THIS_FILE -> CoverStore.SCOPE_FILE to listOf(fileId)
        CoverScope.THIS_ALBUM -> {
            val key = albumKey.ifBlank { fileId }
            CoverStore.SCOPE_ALBUM to listOf(key)
        }
        CoverScope.QUEUE -> {
            val keys = queueAlbumKeys.filter { it.isNotBlank() }.distinct()
            // 队列按专辑分组批量设置；空队列退化为本专辑
            if (keys.isEmpty()) CoverStore.SCOPE_ALBUM to listOf(albumKey.ifBlank { fileId })
            else CoverStore.SCOPE_ALBUM to keys
        }
    }
}
