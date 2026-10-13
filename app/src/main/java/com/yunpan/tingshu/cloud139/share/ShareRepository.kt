package com.yunpan.tingshu.cloud139.share

import com.yunpan.tingshu.cloud139.Cloud139Parser
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.data.model.ScannedTrack
import com.yunpan.tingshu.data.model.ScanProgress
import com.yunpan.tingshu.util.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无登录公开分享仓库（照 share-api.md，已三重验证）。
 * 流程：linkId → openShare(getOutLinkGeneral) → listShareFolder(getOutLinkInfoV6, 首次 pCaID="root") →
 * 音频 presentURL(m3u8) 即直链。body 先加密、对明文算 sign；响应先解密（失败按明文错误 JSON 处理）。
 */
class ShareRepository(
    private val client: OkHttpClient
) {

    data class ShareInfo(
        val linkId: String,
        val name: String,
        val owner: String,
        val raw: String
    )

    data class ShareListing(
        val items: List<CloudFile>,
        val nodNum: Int
    )

    private val jsonMedia = "application/json;charset=UTF-8".toMediaType()

    /** 从分享链接或裸 id 提取 linkId（正则容错）。 */
    fun parseShareLink(input: String): String {
        val s = input.trim()
        Regex("/i/([A-Za-z0-9_-]+)").find(s)?.let { return it.groupValues[1] }
        Regex("/w/i/([A-Za-z0-9_-]+)").find(s)?.let { return it.groupValues[1] }
        // 裸 id：去协议/查询/hash 后取末段
        val cleaned = s.substringBefore('?').substringBefore('#').removeSuffix("/")
        return cleaned.substringAfterLast('/')
    }

    /** 换分享元信息（不翻页）。isPasswd=1。 */
    suspend fun openShare(link: String, passwd: String = ""): ShareInfo {
        val linkId = parseShareLink(link)
        val plain = JSONObject()
            .put("getOutLinkGeneralReq", JSONObject().put("linkID", linkId).put("isPasswd", 1))
            .toString()
        val resp = postShare("/richlifeApp/devapp/IOutLink/getOutLinkGeneral", plain)
        val root = JSONObject(resp)
        val data = root.optJSONObject("data") ?: root
        var name = ""
        var owner = ""
        try {
            val gen = data.optJSONObject("getOutLinkGeneralResp")
            val arr: JSONArray? = gen?.optJSONArray("outLinkGeneral")
            val first = arr?.optJSONObject(0)
            name = first?.optString("lkName").orEmpty()
            owner = first?.optString("ownerUserId").orEmpty()
        } catch (_: Exception) {
        }
        return ShareInfo(linkId, name, owner, resp)
    }

    /**
     * 列目录。首次 pCaID 必须传字面量 "root"（空串报 200000400）。
     * bNum/eNum 为 1 基闭区间序号，页大小 200，srtDr=1 目录在前。
     */
    suspend fun listShareFolder(
        linkId: String,
        pCaId: String,
        passwd: String = "",
        bNum: Int = 1,
        eNum: Int = 200
    ): ShareListing {
        val plain = JSONObject()
            .put(
                "getOutLinkInfoReq", JSONObject()
                    .put("account", "")
                    .put("linkID", linkId)
                    .put("passwd", passwd)
                    .put("caSrt", 0)
                    .put("coSrt", 0)
                    .put("srtDr", 1)
                    .put("bNum", bNum)
                    .put("eNum", eNum)
                    .put("pCaID", pCaId)
            )
            .toString()
        val resp = postShare("/richlifeApp/devapp/IOutLink/getOutLinkInfoV6", plain)
        val listing = parseListing(resp, pCaId)
        // v1.3：给每个条目注入分享坐标（linkId/passwd），供历史/书架冷启动重定位。
        return ShareListing(
            items = listing.items.map { it.copy(shareLinkId = linkId, sharePasswd = passwd) },
            nodNum = listing.nodNum
        )
    }

    /** 解析 getOutLinkInfoV6 响应为 CloudFile 列表（文件夹 + 音频；视频 coType=3 不收）。 */
    internal fun parseListing(body: String, pCaId: String): ShareListing {
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: root
        val nodNum = data.optInt("nodNum", 0)
        val items = ArrayList<CloudFile>()

        val caLst = data.optJSONArray("caLst")
        if (caLst != null) {
            for (i in 0 until caLst.length()) {
                val o = caLst.optJSONObject(i) ?: continue
                val caID = o.optString("caID")
                items.add(
                    CloudFile(
                        id = caID,
                        name = o.optString("caName"),
                        isDir = true,
                        size = 0,
                        updatedAtMs = Cloud139Parser.parseTime(o.optString("udTime")),
                        thumbnailUrl = "",
                        parentId = pCaId,
                        audioExt = null,
                        source = FileSource.SHARE,
                        childToken = caID
                    )
                )
            }
        }

        val coLst = data.optJSONArray("coLst")
        if (coLst != null) {
            for (i in 0 until coLst.length()) {
                val o = coLst.optJSONObject(i) ?: continue
                val coType = o.optInt("coType", 0)
                val suffix = o.optString("coSuffix", "").lowercase()
                if (coType == 2) {
                    // 音频
                    val presentURL = o.optString("presentURL", "")
                    val extInfo = o.optJSONObject("extInfo")
                    val durSec = extInfo?.optString("duration")?.toDoubleOrNull()?.toLong() ?: 0L
                    items.add(
                        CloudFile(
                            id = o.optString("coID"),
                            name = o.optString("coName"),
                            isDir = false,
                            size = o.optLong("coSize", 0L),
                            updatedAtMs = Cloud139Parser.parseTime(o.optString("udTime")),
                            thumbnailUrl = "",
                            parentId = pCaId,
                            audioExt = suffix.ifEmpty { null },
                            source = FileSource.SHARE,
                            directUrl = presentURL.ifEmpty { null },
                            isHls = true,
                            durationSec = durSec,
                            parentToken = pCaId
                        )
                    )
                } else if (coType == 1) {
                    // 图片：作为自动封面候选收进列表（audioExt=null，不计入音频）
                    val thumb = o.optString("thumbnailURL", "").ifEmpty { o.optString("bthumbnailURL", "") }
                    items.add(
                        CloudFile(
                            id = o.optString("coID"),
                            name = o.optString("coName"),
                            isDir = false,
                            size = o.optLong("coSize", 0L),
                            updatedAtMs = Cloud139Parser.parseTime(o.optString("udTime")),
                            thumbnailUrl = thumb,
                            parentId = pCaId,
                            audioExt = suffix.ifEmpty { null },
                            source = FileSource.SHARE,
                            parentToken = pCaId
                        )
                    )
                }
                // 视频 coType=3 / 文档 0 不收
            }
        }
        return ShareListing(items, nodNum)
    }

    /** 递归扫描分享根下音频（BFS，复用深度语义；根 childToken="root"）。 */
    suspend fun scanShareAudio(
        linkId: String,
        passwd: String,
        depth: ScanDepth,
        progress: ((ScanProgress) -> Unit)? = null
    ): List<ScannedTrack> {
        val maxDepth = depth.maxDepth()
        data class Q(val pCaId: String, val level: Int, val albumPath: List<String>)
        val queue = ArrayDeque<Q>()
        queue.add(Q("root", 0, emptyList()))
        val collected = ArrayList<ScannedTrack>()
        var visitedFolders = 0

        while (queue.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) break
            val (pCaId, level, albumPath) = queue.removeFirst()
            // 翻页拉全该文件夹
            val all = ArrayList<CloudFile>()
            var bNum = 1
            val pageSize = 200
            var nodNum = Int.MAX_VALUE
            while (bNum <= nodNum && currentCoroutineContext().isActive) {
                val listing = listShareFolder(linkId, pCaId, passwd, bNum, bNum + pageSize - 1)
                nodNum = listing.nodNum
                all += listing.items
                if (listing.items.isEmpty()) break
                bNum += pageSize
                if (all.size >= 2000) break
            }
            visitedFolders++

            for (f in all) {
                if (f.isAudio) collected.add(ScannedTrack(f.withAlbumPath(albumPath), albumPath))
            }
            progress?.invoke(ScanProgress(visitedFolders, collected.size, albumPath.lastOrNull() ?: "root"))

            if ((maxDepth < 0 || level < maxDepth) && currentCoroutineContext().isActive) {
                for (sub in all) {
                    if (sub.isDir) queue.add(Q(sub.childToken ?: sub.id, level + 1, albumPath + sub.name))
                }
            }
            if (visitedFolders >= 200 || collected.size >= 2000) break
        }

        val grouped = collected.groupBy { it.albumPath }
        val orderedKeys = grouped.keys.sortedWith { a, b -> comparePath(a, b) }
        val out = ArrayList<ScannedTrack>(collected.size)
        for (key in orderedKeys) {
            val group = grouped[key]!!.sortedWith { x, y -> NaturalOrder.compare(x.file.name, y.file.name) }
            out.addAll(group)
        }
        return out
    }

    private fun comparePath(a: List<String>, b: List<String>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val sa = a.getOrNull(i); val sb = b.getOrNull(i)
            val c = when {
                sa == null && sb == null -> continue
                sa == null -> -1
                sb == null -> 1
                else -> NaturalOrder.compare(sa, sb)
            }
            if (c != 0) return c
        }
        return 0
    }

    // ---------- 加密+签名 POST ----------

    private suspend fun postShare(endpoint: String, plainJson: String): String = withContext(Dispatchers.IO) {
        val (_, _, signHeader) = ShareSign.build(plainJson)
        val encBody = OutlinkCrypto.encrypt(plainJson)
        val url = BASE + endpoint
        val builder = Request.Builder().url(url).post(encBody.toRequestBody(jsonMedia))
        for ((k, v) in shareHeaders(signHeader)) builder.header(k, v)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            // 先解密；失败按明文错误 JSON 处理
            val decrypted = try {
                OutlinkCrypto.decrypt(text)
            } catch (e: Exception) {
                text
            }
            // 检测分享链接错误码
            detectShareError(decrypted)
            decrypted
        }
    }

    /**
     * 检测分享链接常见错误：链接失效、密码错误、链接不存在等。
     * 命中则抛 [ShareLinkInvalidException]，否则返回 null。
     */
    private fun detectShareError(body: String) {
        val root = try { JSONObject(body) } catch (e: Exception) { return }
        val code = root.optString("resultCode", root.optString("code", ""))
        val msg = root.optString("resultDesc", root.optString("message", ""))

        when (code) {
            "200000727" -> throw ShareLinkInvalidException(
                msg.ifEmpty { "分享链接已失效或被删除" }, code
            )
            "200000400", "200000401" -> throw ShareLinkInvalidException(
                msg.ifEmpty { "分享链接不存在或提取码错误" }, code
            )
            "200000100", "200000101" -> throw ShareLinkInvalidException(
                msg.ifEmpty { "提取码错误，请检查后重试" }, code
            )
            else -> {
                // success=false 且有明确 message 时也抛（兜底）
                val success = root.optBoolean("success", true)
                if (!success && msg.isNotBlank() && code.isNotBlank()) {
                    throw ShareLinkInvalidException(msg, code)
                }
            }
        }
    }

    private fun shareHeaders(signHeader: String): Map<String, String> = linkedMapOf(
        "Content-Type" to "application/json;charset=UTF-8",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://yun.139.com",
        "Referer" to "https://yun.139.com/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "X-Deviceinfo" to "||9|12.27.0|chrome|146.0.0.0|01c8e9ddaf469105d11f67966323ef48||linux unknow|1920X993|zh-CN|||",
        "hcy-cool-flag" to "1",
        "CMS-DEVICE" to "default",
        "x-yun-channel-source" to "10213406",
        "x-huawei-channelSrc" to "10213406",
        "x-SvcType" to "1",
        "x-yun-svc-type" to "1",
        "x-m4c-caller" to "PC",
        "x-m4c-src" to "10002",
        "x-inner-ntwk" to "2",
        "mcloud-route" to "001",
        "mcloud-version" to "7.13.3",
        "mcloud-channel" to "1000101",
        "mcloud-client" to "10702",
        "mcloud-sign" to signHeader,
        "mcloud-skey" to "",
        "INNER-HCY-ROUTER-HTTPS" to "1",
        "x-yun-module-type" to "100",
        "x-yun-app-channel" to "10213406",
        "x-yun-api-version" to "v1"
    )

    companion object {
        const val BASE = "https://share-kd-njs.yun.139.com/yun-share"
    }
}
