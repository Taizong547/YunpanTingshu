package com.yunpan.tingshu.cloud139

import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.ListPage
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
import java.io.IOException

/**
 * 个人云盘 139 API（OkHttp4，实现 Cloud139Repository）。
 * 照 SPEC §3 / dirfilelist.ts：route 路由 + host 缓存、候选 URL（仅 404/route not found 切换）、
 * 首页失败回退老接口 getDisk、getDownloadUrl 取直链、BFS 递归扫描。
 *
 * 纯 JVM 可测（依赖注入 OkHttpClient，测试用 MockWebServer）。
 */
class Cloud139Api(
    private val client: OkHttpClient
) : Cloud139Repository {

    private val hostCache = HashMap<String, String>()

    private val jsonMedia = "application/json;charset=UTF-8".toMediaType()
    private val routeNotFound = Regex("route not found", RegexOption.IGNORE_CASE)

    private data class HttpResult(val status: Int, val body: String)

    override fun streamHeaders(): Map<String, String> = Cloud139Headers.streamHeaders()

    override fun clearHostCache() { hostCache.clear() }

    // ---------- 底层 POST ----------

    private suspend fun post(
        url: String,
        body: String,
        rawToken: String,
        route: Boolean
    ): HttpResult = withContext(Dispatchers.IO) {
        val headers = Cloud139Headers.build(rawToken, body, route)
        val builder = Request.Builder().url(url).post(body.toRequestBody(jsonMedia))
        for ((k, v) in headers) builder.header(k, v)
        val req = builder.build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            runCatching {
                android.util.Log.d(
                    "Cloud139Api",
                    "POST $url -> HTTP ${resp.code} | reqHeaders=${headers.keys.joinToString(",")} | resp=${body.take(200)}"
                )
            }
            HttpResult(resp.code, body)
        }
    }

    // ---------- 路由 ----------

    private suspend fun getHost(account: CloudAccount): String {
        hostCache[account.id]?.let { return it }
        val reqBody = JSONObject()
            .put("userInfo", JSONObject().put("userType", 1).put("accountType", 1).put("accountName", account.phone))
            .put("modAddrType", 1)
            .toString()
        val res = post(Cloud139Headers.ROUTE_POLICY_URL, reqBody, account.rawToken, route = true)

        // 路由请求也检测 token 过期
        isTokenExpired(res.status, res.body)?.let { throw TokenExpiredException(it) }

        val root = try { JSONObject(res.body) } catch (e: Exception) { JSONObject() }
        if (res.status !in 200..299 || root.optBoolean("success", true) == false) {
            throw IOException(extractMessage(res.body) ?: "139 云盘路由请求失败 HTTP ${res.status}")
        }
        val data = root.optJSONObject("data") ?: root
        val routeList: JSONArray = data.optJSONArray("routePolicyList")
            ?: root.optJSONArray("routePolicyList")
            ?: JSONArray()
        var personal: JSONObject? = null
        for (i in 0 until routeList.length()) {
            val o = routeList.optJSONObject(i) ?: continue
            if (o.optString("modName") == "personal") { personal = o; break }
        }
        if (personal == null && routeList.length() > 0) personal = routeList.optJSONObject(0)
        val host = personal?.optString("httpsUrl")?.takeIf { it.isNotEmpty() }
            ?: personal?.optString("httpUrl")?.takeIf { it.isNotEmpty() }
            ?: ""
        if (host.isEmpty()) throw IOException("139 云盘路由未返回 PersonalCloudHost")
        hostCache[account.id] = host
        return host
    }

    // ---------- 带候选切换的业务请求 ----------

    private suspend fun callPersonal(account: CloudAccount, endpoint: String, bodyObj: JSONObject): String {
        val host = getHost(account)
        val postBody = bodyObj.toString()
        var lastError = ""
        for (url in Cloud139Urls.personalUrls(host, endpoint)) {
            val res = post(url, postBody, account.rawToken, route = false)
            if (res.status in 200..299 && isBusinessSuccess(res.body)) return res.body

            // 优先检测 token 过期（HTTP 401/403 或业务码 9008/9100）
            val tokenExpired = isTokenExpired(res.status, res.body)
            if (tokenExpired != null) throw TokenExpiredException(tokenExpired)

            lastError = extractMessage(res.body) ?: "139 云盘请求失败 HTTP ${res.status}"
            // 仅 404 或 message 匹配 /route not found/i 才换候选，其余错误原样抛
            if (res.status != 404 && !routeNotFound.containsMatchIn(lastError)) throw IOException(lastError)
        }
        throw IOException(lastError.ifEmpty { "139 云盘请求失败" })
    }

    /**
     * 检测是否为 token 过期/鉴权失败。是则返回错误文案，否则返回 null。
     * 触发条件：
     * - HTTP 401 / 403
     * - 业务码 9008（Token 已过期）
     * - 业务码 9100（权限不足，大概率是 token 失效）
     */
    private fun isTokenExpired(httpStatus: Int, body: String): String? {
        if (httpStatus == 401 || httpStatus == 403) {
            return "登录已过期（HTTP $httpStatus），请重新登录"
        }
        val root = try { JSONObject(body) } catch (e: Exception) { return null }
        val code = root.optString("code", "")
        val msg = root.optString("message", "").ifEmpty { root.optString("error_msg", "") }
        return when (code) {
            "9008" -> "登录已过期：${msg.ifEmpty { "Token 已过期" }}，请重新登录"
            "9100" -> "登录已失效：${msg.ifEmpty { "权限不足" }}，请重新登录"
            else -> null
        }
    }

    /** 成功判定：success!=false 且 code 不在 {9000,9008,9100}。 */
    private fun isBusinessSuccess(body: String): Boolean {
        val root = try { JSONObject(body) } catch (e: Exception) { return false }
        if (root.optBoolean("success", true) == false) return false
        val code = root.optString("code", "")
        if (code in Cloud139Headers.ERROR_CODES) return false
        return true
    }

    private fun extractMessage(body: String): String? {
        val root = try { JSONObject(body) } catch (e: Exception) { return null }
        val m = root.optString("message", "").ifEmpty { root.optString("msg", "") }
            .ifEmpty { root.optString("error_msg", "") }
        return m.ifEmpty { null }
    }

    // ---------- 列表 ----------

    override suspend fun listFolder(account: CloudAccount, parentId: String, cursor: String): ListPage {
        val parent = Cloud139Urls.apiParentId(parentId)
        val bodyObj = JSONObject()
            .put("imageThumbnailStyleList", JSONArray().put("Small").put("Large"))
            .put("orderBy", "updated_at")
            .put("orderDirection", "DESC")
            .put("pageInfo", JSONObject().put("pageCursor", cursor).put("pageSize", 100))
            .put("parentFileId", parent)
        return try {
            val text = callPersonal(account, "/file/list", bodyObj)
            Cloud139Parser.listFromResponse(text, parent, cursor)
        } catch (e: Exception) {
            // 仅首页（cursor 空）且新接口报 404/route not found 时才回退老接口 getDisk；
            // 业务错误（9000/9008/9100 等）原样抛出，不回退。
            if (cursor.isEmpty() && routeNotFound.containsMatchIn(e.message ?: "")) {
                val items = oldList(account, parentId)
                ListPage(items, "")
            } else throw e
        }
    }

    private suspend fun oldList(account: CloudAccount, parentId: String): List<CloudFile> {
        val bodyObj = JSONObject()
            .put("catalogID", Cloud139Urls.oldApiParentId(parentId))
            .put("sortDirection", 1)
            .put("startNumber", 1)
            .put("endNumber", 100)
            .put("filterType", 0)
            .put("catalogSortType", 0)
            .put("contentSortType", 0)
            .put("commonAccountInfo", JSONObject().put("account", account.phone).put("accountType", 1))
            .toString()
        val url = "https://yun.139.com/orchestration/personalCloud/catalog/v1.0/getDisk"
        val res = post(url, bodyObj, account.rawToken, route = false)
        if (res.status !in 200..299) throw IOException(extractMessage(res.body) ?: "139 云盘老接口请求失败 HTTP ${res.status}")
        val root = try { JSONObject(res.body) } catch (e: Exception) { JSONObject() }
        if (root.optBoolean("success", true) == false) {
            throw IOException(extractMessage(res.body) ?: "139 云盘老接口请求失败")
        }
        return Cloud139Parser.listFromResponse(res.body, Cloud139Urls.oldApiParentId(parentId), "").items
    }

    override suspend fun listAllAudio(account: CloudAccount, folderId: String): List<CloudFile> {
        val result = ArrayList<CloudFile>()
        var cursor = ""
        var pages = 0
        do {
            val page = listFolder(account, folderId, cursor)
            result += page.items.filter { it.isAudio }
            cursor = page.nextCursor
            pages++
        } while (cursor.isNotEmpty() && result.size < MAX_TRACKS && pages < 100)
        return result
    }

    // ---------- 直链 ----------

    override suspend fun getPlayUrl(account: CloudAccount, fileId: String): String {
        // 个人云盘直链端点 139 可能已迁移：依次尝试若干候选端点，哪个返回有效下载链接就用哪个
        val endpoints = listOf(
            "/file/getDownloadUrl",
            "/file/v2/getDownloadUrl",
            "/personal/file/getDownloadUrl",
            "/personalCloud/file/getDownloadUrl"
        )
        var lastErr: String = "139 云盘未返回下载链接"
        for (ep in endpoints) {
            try {
                val text = callPersonal(account, ep, JSONObject().put("fileId", fileId))
                val url = extractDownloadUrl(text)
                if (url.isNotEmpty()) {
                    runCatching { android.util.Log.d("Cloud139Api", "getPlayUrl OK via $ep -> $url") }
                    return url
                }
            } catch (t: Throwable) {
                lastErr = t.message ?: ep
                runCatching { android.util.Log.w("Cloud139Api", "getPlayUrl $ep failed: $lastErr") }
                // token 过期直接抛，不试候选
                if (t is TokenExpiredException) throw t
            }
        }
        throw IOException(lastErr)
    }

    /** 从响应体兼容多种字段名提取下载直链。 */
    private fun extractDownloadUrl(text: String): String {
        val root = try { JSONObject(text) } catch (e: Exception) { return "" }
        // 业务失败码不算成功
        val code = root.optString("code", "")
        if (code in Cloud139Headers.ERROR_CODES) return ""
        val data = root.optJSONObject("data") ?: root
        for (key in listOf("cdnUrl", "downloadUrl", "url", "downloadLink", "fileUrl")) {
            val v = data.optString(key).ifEmpty { root.optString(key) }
            if (v.startsWith("http")) return v
        }
        return ""
    }

    // ---------- 递归扫描（SPEC §10.3）----------

    override suspend fun scanAudio(
        account: CloudAccount,
        rootFolderId: String,
        depth: ScanDepth,
        progress: ((ScanProgress) -> Unit)?
    ): List<ScannedTrack> {
        val maxDepth = depth.maxDepth() // -1 = ALL
        val rootId = Cloud139Urls.apiParentId(rootFolderId)
        data class Q(val folderId: String, val level: Int, val albumPath: List<String>)
        val queue = ArrayDeque<Q>()
        queue.add(Q(rootId, 0, emptyList()))
        val collected = ArrayList<ScannedTrack>()
        var visitedFolders = 0

        while (queue.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) break
            val (folderId, level, albumPath) = queue.removeFirst()
            // 拉全该文件夹（按游标翻页）
            val all = ArrayList<CloudFile>()
            var cursor = ""
            do {
                val page = listFolder(account, folderId, cursor)
                all += page.items
                cursor = page.nextCursor
            } while (cursor.isNotEmpty() && all.size < MAX_TRACKS && currentCoroutineContext().isActive)
            visitedFolders++

            for (f in all) {
                if (f.isAudio) collected.add(ScannedTrack(f.withAlbumPath(albumPath), albumPath))
            }
            progress?.invoke(ScanProgress(visitedFolders, collected.size, albumPath.lastOrNull() ?: rootId))

            if ((maxDepth < 0 || level < maxDepth) && currentCoroutineContext().isActive) {
                for (sub in all) {
                    if (sub.isDir) queue.add(Q(sub.id, level + 1, albumPath + sub.name))
                }
            }
            if (visitedFolders >= MAX_FOLDERS || collected.size >= MAX_TRACKS) break
        }

        // 分组：按 albumPath，组内按文件名自然排序；组间按目录分段自然排序
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
            val sa = a.getOrNull(i)
            val sb = b.getOrNull(i)
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

    companion object {
        const val MAX_TRACKS = 2000
        const val MAX_FOLDERS = 200
    }
}
