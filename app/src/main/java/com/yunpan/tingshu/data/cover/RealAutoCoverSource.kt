package com.yunpan.tingshu.data.cover

import com.yunpan.tingshu.cloud139.Cloud139Repository
import com.yunpan.tingshu.cloud139.share.ShareRepository
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.util.Clock
import com.yunpan.tingshu.util.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动封面真实网络源（COVER_ART_SPEC §4/§5.3，锦上添花）。
 * - SHARE：listShareFolder(parentToken) 取 coType=1 图片 thumbnailURL（无头 Remote）。
 * - 个人云：listFolder(parentToken) 找图片扩展名 → getDownloadUrl(contentID) 带 UA/Referer/Origin。
 * 目录结果短 TTL 缓存；parentToken 缺失或任何失败 → null（不做全树扫描，不影响播放）。
 */
class RealAutoCoverSource(
    private val cloudRepo: Cloud139Repository,
    private val shareRepo: ShareRepository,
    private val accountProvider: suspend () -> CloudAccount?,
    private val clock: Clock = SystemClock
) : AutoCoverSource {

    private data class Cached(val atMs: Long, val url: String?)
    private val cache = ConcurrentHashMap<String, Cached>()

    override suspend fun autoFor(file: CloudFile): ArtworkRef.Remote? {
        return try {
            val pca = file.parentToken?.takeIf { it.isNotEmpty() }
                ?: file.parentId.takeIf { it.isNotEmpty() }
                ?: return null
            val key = "${file.source}:$pca"
            cache[key]?.let { c ->
                if (clock.nowMs() - c.atMs < TTL) return c.url?.let { ArtworkRef.Remote(it, headersFor(file)) }
            }
            val url = when (file.source) {
                FileSource.SHARE -> shareCover(file.shareLinkId ?: "", file.sharePasswd ?: "", pca)
                FileSource.CLOUD139 -> cloudCover(pca)
            }
            cache[key] = Cached(clock.nowMs(), url)
            url?.let { ArtworkRef.Remote(it, headersFor(file)) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun shareCover(linkId: String, passwd: String, pca: String): String? {
        if (linkId.isEmpty()) return null
        runCatching { shareRepo.openShare(linkId, passwd) }
        val listing = shareRepo.listShareFolder(linkId, pca, passwd)
        val imgs = listing.items.filter { !it.isDir && it.thumbnailUrl.isNotEmpty() }
        if (imgs.isEmpty()) return null
        val preferred = imgs.firstOrNull {
            it.name.contains("cover", ignoreCase = true) ||
                it.name.contains("封面") ||
                it.name.contains("folder", ignoreCase = true)
        }
        return (preferred ?: imgs.first()).thumbnailUrl
    }

    private suspend fun cloudCover(pca: String): String? {
        val account = accountProvider() ?: return null
        val page = cloudRepo.listFolder(account, pca, "")
        val imgs = page.items.filter { !it.isDir && it.audioExt in IMAGE_EXTS }
        if (imgs.isEmpty()) return null
        val preferred = imgs.firstOrNull {
            it.name.contains("cover", ignoreCase = true) ||
                it.name.contains("封面") ||
                it.name.contains("folder", ignoreCase = true)
        }
        return cloudRepo.getPlayUrl(account, (preferred ?: imgs.first()).id)
    }

    private fun headersFor(file: CloudFile): Map<String, String> = when (file.source) {
        FileSource.SHARE -> emptyMap()
        FileSource.CLOUD139 -> cloudRepo.streamHeaders()
    }

    companion object {
        private const val TTL = 5 * 60_000L   // 目录结果短缓存 5 分钟
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")
    }
}
