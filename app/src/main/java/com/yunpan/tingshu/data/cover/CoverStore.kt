package com.yunpan.tingshu.data.cover

import com.yunpan.tingshu.data.db.Cover
import com.yunpan.tingshu.data.db.CoverDao
import com.yunpan.tingshu.data.db.CoverOverride
import com.yunpan.tingshu.data.db.CoverOverrideDao
import com.yunpan.tingshu.util.Clock
import com.yunpan.tingshu.util.SystemClock
import java.security.MessageDigest

/**
 * 封面仓储（COVER_ART_SPEC §2.5/§5）。内容寻址：同图全 App 一份物理文件；映射只存引用关系。
 * 换图/取消映射时按引用计数清理孤立物理文件。纯本地，绝不改云端、不下载音频。
 */
class CoverStore(
    private val coverDao: CoverDao,
    private val overrideDao: CoverOverrideDao,
    private val storage: CoverStorage,
    private val compressor: ImageCompressor,
    private val clock: Clock = SystemClock
) {

    /** 导入原始图片字节 → 压缩、哈希、落盘（已存在则复用）→ 返回 coverId。 */
    suspend fun importFromBytes(bytes: ByteArray, source: String = SOURCE_GALLERY): String {
        val compressed = compressor.compress(bytes)
        val coverId = sha256Hex(compressed).take(16)
        if (!storage.exists(coverId)) storage.write(coverId, compressed)
        coverDao.insertCover(Cover(coverId, compressed.size.toLong(), source, clock.nowMs()))
        return coverId
    }

    /** 设置单曲封面。返回 coverId。 */
    suspend fun setFileCover(fileId: String, coverId: String) = upsert(SCOPE_FILE, fileId, coverId)

    /** 设置专辑封面（albumKey = 文件夹 token / albumPath）。 */
    suspend fun setAlbumCover(albumKey: String, coverId: String) = upsert(SCOPE_ALBUM, albumKey, coverId)

    /** 批量：多个 key 指向同一张 coverId（只写映射，不复制图片）。 */
    suspend fun batchSet(scope: String, keys: List<String>, coverId: String) {
        val now = clock.nowMs()
        overrideDao.batchUpsert(keys.map { CoverOverride(scope = scope, targetKey = it, coverId = coverId, updatedAt = now) })
    }

    /** 清除某 key 的覆盖；旧 coverId 引用归 0 时删物理文件 + Cover 记录。 */
    suspend fun clear(scope: String, targetKey: String) {
        val existing = overrideDao.getOverride(scope, targetKey) ?: return
        overrideDao.deleteOverride(scope, targetKey)
        maybeOrphanCleanup(existing.coverId)
    }

    /** 返回 scope/key 映射指向的本地物理路径（文件存在才返回），否则 null。 */
    suspend fun localPathFor(scope: String, targetKey: String): String? {
        val ov = overrideDao.getOverride(scope, targetKey) ?: return null
        if (!storage.exists(ov.coverId)) return null
        return storage.fileFor(ov.coverId).absolutePath
    }

    suspend fun countRefs(coverId: String): Int = coverDao.countRefs(coverId)

    // ---------- internal ----------

    private suspend fun upsert(scope: String, targetKey: String, coverId: String): String {
        val old = overrideDao.getOverride(scope, targetKey)
        overrideDao.upsert(CoverOverride(scope = scope, targetKey = targetKey, coverId = coverId, updatedAt = clock.nowMs()))
        if (old != null && old.coverId != coverId) maybeOrphanCleanup(old.coverId)
        return coverId
    }

    private suspend fun maybeOrphanCleanup(coverId: String) {
        if (coverDao.countRefs(coverId) == 0) {
            storage.delete(coverId)
            coverDao.deleteCover(coverId)
        }
    }

    companion object {
        const val SCOPE_FILE = "FILE"
        const val SCOPE_ALBUM = "ALBUM"
        const val SOURCE_GALLERY = "GALLERY"
        const val SOURCE_CLOUD = "CLOUD"

        fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
