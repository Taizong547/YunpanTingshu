package com.yunpan.tingshu.data

import com.yunpan.tingshu.cloud139.Cloud139Repository
import com.yunpan.tingshu.cloud139.share.ShareRepository
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.MediaSpec
import com.yunpan.tingshu.data.model.PlaybackResolver
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.util.Clock
import com.yunpan.tingshu.util.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * 默认播放解析器。
 * - CLOUD139：用 active 账号按 fileId 现取直链（streamHeaders）。
 * - SHARE：若 CloudFile 已带 directUrl（新扫描/刚列目录）直接用；冷启动恢复的曲目 directUrl=null，
 *   按 shareLinkId+sharePasswd 重新 openShare（按 linkId 缓存，TTL 对齐直链约 8h），
 *   再用 parentToken(pCaID) 翻页列目录找 coID==file.id 的 presentURL(m3u8)。
 *   父 token 缺失时从 root 做有界扫描兜底。找不到/失效抛中文异常，交给播放层错误态。
 */
class DefaultPlaybackResolver(
    private val repo: Cloud139Repository,
    private val shareRepo: ShareRepository,
    private val clock: Clock = SystemClock,
    private val accountProvider: suspend () -> CloudAccount?
) : PlaybackResolver {

    // linkId -> openShare 成功时刻（ms）
    private val openCache = ConcurrentHashMap<String, Long>()

    override suspend fun resolve(file: CloudFile): MediaSpec {
        return when (file.source) {
            FileSource.SHARE -> resolveShare(file)
            FileSource.CLOUD139 -> {
                val account = accountProvider()
                    ?: throw IllegalStateException("未登录 139 云盘账号，请先在「我的」页粘贴 Authorization")
                val url = repo.getPlayUrl(account, file.id)
                MediaSpec(
                    uri = url,
                    headers = repo.streamHeaders(),
                    isHls = isHlsUrl(url)
                )
            }
        }
    }

    /**
     * 强制新鲜解析：绕过 presentURL 快路径与 openShare 缓存（直链/清单鉴权过期时由播放层调用）。
     * SHARE：清 linkId 缓存并把 directUrl 置空，重新 openShare + 按坐标列目录取新鲜 presentURL；
     * CLOUD139：getPlayUrl 每次现取，天然新鲜，等同 [resolve]。
     */
    override suspend fun resolveFresh(file: CloudFile): MediaSpec = when (file.source) {
        FileSource.SHARE -> {
            file.shareLinkId?.takeIf { it.isNotEmpty() }?.let { openCache.remove(it) }
            resolveShare(file.copy(directUrl = null))
        }
        FileSource.CLOUD139 -> resolve(file)
    }

    // ---------- SHARE ----------

    private suspend fun resolveShare(file: CloudFile): MediaSpec {
        // 快路径：刚列目录/扫描出来的曲目已带 presentURL。
        file.directUrl?.let { return MediaSpec(it, emptyMap(), true) }

        val linkId = file.shareLinkId?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("分享信息缺失，无法恢复播放，请重新进入该分享")
        val passwd = file.sharePasswd ?: ""

        openShareCached(linkId, passwd)

        // 1) 优先用父 pCaID 列目录找。
        val pCa = file.parentToken?.takeIf { it.isNotEmpty() }
        var present = findInFolder(linkId, passwd, pCa ?: "root", file.id)

        // 2) 父 token 缺失，或在父目录没找到（曲目可能被移动）→ 从 root 有界扫描兜底。
        if (present == null) {
            present = findByRootScan(linkId, passwd, file.id)
        }

        return MediaSpec(
            uri = present ?: throw IllegalStateException("分享已失效或该音频已被移除，请重新进入分享"),
            headers = emptyMap(),
            isHls = true
        )
    }

    /** openShare 按 linkId 缓存，TTL 约 8h（对齐直链有效期）。 */
    private suspend fun openShareCached(linkId: String, passwd: String) {
        val now = clock.nowMs()
        val cachedAt = openCache[linkId]
        if (cachedAt != null && now - cachedAt < OPEN_TTL_MS) return
        try {
            shareRepo.openShare(linkId, passwd)
            openCache[linkId] = now
        } catch (e: Exception) {
            openCache.remove(linkId)
            throw IllegalStateException("分享打开失败（链接失效或提取码错误）：${e.message ?: "未知错误"}")
        }
    }

    /** 翻页列 pCa 目录，找 coID==targetId 且带 presentURL 的音频。 */
    private suspend fun findInFolder(linkId: String, passwd: String, pCa: String, targetId: String): String? {
        var bNum = 1
        val pageSize = 200
        var nodNum = Int.MAX_VALUE
        var pages = 0
        while (bNum <= nodNum && pages < MAX_FOLDER_PAGES) {
            val listing = shareRepo.listShareFolder(linkId, pCa, passwd, bNum, bNum + pageSize - 1)
            nodNum = listing.nodNum
            listing.items.firstOrNull { it.id == targetId && !it.isDir }?.directUrl?.let { return it }
            if (listing.items.isEmpty()) break
            bNum += pageSize
            pages++
        }
        return null
    }

    /** root 有界扫描兜底（复用 scanShareAudio 上限：200 文件夹 / 2000 曲目）。 */
    private suspend fun findByRootScan(linkId: String, passwd: String, targetId: String): String? {
        return try {
            val tracks = shareRepo.scanShareAudio(linkId, passwd, ScanDepth.ALL, null)
            tracks.firstOrNull { it.file.id == targetId }?.file?.directUrl
        } catch (e: Exception) {
            null
        }
    }

    private fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    companion object {
        private const val OPEN_TTL_MS = 8L * 60 * 60 * 1000   // 8h
        private const val MAX_FOLDER_PAGES = 20
    }
}
