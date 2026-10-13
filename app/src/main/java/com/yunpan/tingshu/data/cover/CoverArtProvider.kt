package com.yunpan.tingshu.data.cover

import com.yunpan.tingshu.data.model.CloudFile

/**
 * 封面解析优先级（COVER_ART_SPEC §5，纯逻辑可单测）：
 * FILE 覆盖(本地) > ALBUM 覆盖(本地) > 自动文件夹封面(网络 URL) > 文件自带缩略图(网络) > null。
 */
class CoverArtProvider(
    private val store: CoverStore,
    private val autoCover: AutoCoverSource? = null
) {

    suspend fun artworkFor(file: CloudFile): ArtworkRef? {
        // 1. 单曲覆盖
        store.localPathFor(CoverStore.SCOPE_FILE, file.id)?.let { return ArtworkRef.Local(it) }
        // 2. 专辑覆盖（key = 所在目录 pCaID / parentId）
        val albumKey = file.parentToken?.takeIf { it.isNotEmpty() }
            ?: file.parentId.takeIf { it.isNotEmpty() }
        if (albumKey != null) {
            store.localPathFor(CoverStore.SCOPE_ALBUM, albumKey)?.let { return ArtworkRef.Local(it) }
        }
        // 3. 自动文件夹封面（网络 URL，Coil 缓存）
        autoCover?.autoFor(file)?.let { return it }
        // 4. 文件自带缩略图
        file.thumbnailUrl.takeIf { it.isNotEmpty() }?.let { return ArtworkRef.Remote(it) }
        return null
    }
}
