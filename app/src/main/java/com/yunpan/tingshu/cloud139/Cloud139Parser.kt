package com.yunpan.tingshu.cloud139

import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.ListPage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 列表解析（对应 TS listFromResponse + mapCloud139FileToAliModel，照 SPEC §3.6 / dirfilelist.ts:145-151,206-237）。
 * 多重兜底字段、游标、老接口 contentList 拼接、时间两种格式（ISO 与 yyyyMMddHHmmss）。
 */
object Cloud139Parser {

    private val EAST8: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    fun listFromResponse(body: String, parentId: String, inputCursor: String): ListPage {
        val root = JSONObject(body)
        val raw = if (root.has("data") && !root.isNull("data")) root.getJSONObject("data") else root

        val getDisk = if (raw.has("getDiskResult") && !raw.isNull("getDiskResult")) {
            raw.getJSONObject("getDiskResult")
        } else null

        var list: JSONArray = optArray(raw, "items")
            ?: optArray(raw, "list")
            ?: optArray(raw, "content")
            ?: optArray(raw, "files")
            ?: optArray(raw, "fileList")
            ?: getDisk?.let { optArray(it, "catalogList") }
            ?: JSONArray()
        val contentList = getDisk?.let { optArray(it, "contentList") }
        // TS: 二者都为数组且 contentList 非空则拼接，否则返回 list
        val merged = if (contentList != null && contentList.length() > 0) concat(list, contentList) else list

        val items = ArrayList<CloudFile>(merged.length())
        for (i in 0 until merged.length()) {
            val it = merged.getJSONObject(i)
            items += mapItem(it, parentId)
        }

        val pageInfo = if (raw.has("pageInfo") && !raw.isNull("pageInfo")) raw.getJSONObject("pageInfo") else null
        var next = pageInfo?.optString("nextPageCursor")?.takeIf { it.isNotEmpty() }
            ?: pageInfo?.optString("nextCursor")?.takeIf { it.isNotEmpty() }
            ?: raw.optString("nextPageCursor").takeIf { it.isNotEmpty() }
            ?: raw.optString("nextCursor").takeIf { it.isNotEmpty() }
            ?: ""
        if (next == inputCursor) next = ""
        return ListPage(items, next)
    }

    private fun concat(a: JSONArray, b: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until a.length()) out.put(a.get(i))
        for (i in 0 until b.length()) out.put(b.get(i))
        return out
    }

    private fun mapItem(it: JSONObject, parentId: String): CloudFile {
        val id = str(it.opt("fileId"))
            .ifEmpty { str(it.opt("catalogId")) }
            .ifEmpty { str(it.opt("catalogID")) }
            .ifEmpty { str(it.opt("contentID")) }
        val name = str(it.opt("name"))
            .ifEmpty { str(it.opt("catalogName")) }
            .ifEmpty { str(it.opt("contentName")) }
        val isDir = it.optString("type") == "folder" ||
                (it.has("catalogId") && !it.isNull("catalogId")) ||
                (it.has("catalogID") && !it.isNull("catalogID"))
        val size = numLong(it.opt("size")).takeIf { it > 0 } ?: numLong(it.opt("contentSize"))
        val timeRaw = str(it.opt("updated_at"))
            .ifEmpty { str(it.opt("updatedAt")) }
            .ifEmpty { str(it.opt("updateTime")) }
            .ifEmpty { str(it.opt("created_at")) }
            .ifEmpty { str(it.opt("createTime")) }
        val updatedAtMs = parseTime(timeRaw)
        val thumbnail = thumbnail(it)
        val ext = if (isDir) null else AudioExt.extOf(name)
        return CloudFile(
            id = id,
            name = name,
            isDir = isDir,
            size = size,
            updatedAtMs = updatedAtMs,
            thumbnailUrl = thumbnail,
            parentId = parentId,
            audioExt = ext,
            source = FileSource.CLOUD139,
            childToken = if (isDir) id else null
        )
    }

    private fun thumbnail(it: JSONObject): String {
        val thumbs = if (it.has("thumbnailUrls") && !it.isNull("thumbnailUrls")) it.optJSONArray("thumbnailUrls") else null
        if (thumbs != null) {
            for (i in 0 until thumbs.length()) {
                val t = thumbs.optJSONObject(i) ?: continue
                val u = t.optString("url", "")
                if (u.isNotEmpty()) return u
            }
        }
        val direct = it.optString("thumbnailURL", "")
        return direct
    }

    private fun optArray(o: JSONObject, key: String): JSONArray? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.get(key)
        return if (v is JSONArray) v else null
    }

    private fun str(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Number -> v.toString()
        else -> v.toString()
    }

    private fun numLong(v: Any?): Long = when (v) {
        null -> 0L
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: 0L
        else -> 0L
    }

    /** 时间：兼容 14 位 yyyyMMddHHmmss（东八区）与 ISO8601。 */
    fun parseTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        val s = raw.trim()
        if (s.matches(Regex("^\\d{14}$"))) {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.timeZone = EAST8
            return try { fmt.parse(s)?.time ?: 0L } catch (e: Exception) { 0L }
        }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                if (!p.endsWith("XXX") && !p.endsWith("'Z'")) fmt.timeZone = EAST8
                val d: Date? = fmt.parse(s)
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }
}
