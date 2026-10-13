package com.yunpan.tingshu.data.cover

import com.yunpan.tingshu.data.model.CloudFile

/**
 * 封面解析结果。Local=已落地本地文件路径；Remote=网络 URL（Coil 缓存，可能带认证头）。
 * 返回 null 表示无封面，UI 用主题占位。
 */
sealed class ArtworkRef {
    data class Local(val path: String) : ArtworkRef()
    data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : ArtworkRef()
}

/**
 * 自动专辑封面来源（注入）：列出同目录图片，选一张 thumbnail URL。
 * 优先分享 coType=1 的公开 thumbnailURL（无头）；个人云用文件自带 thumbnailUrl。
 * 不落地本地文件（Coil 缓存）。
 */
interface AutoCoverSource {
    suspend fun autoFor(file: CloudFile): ArtworkRef.Remote?
}
