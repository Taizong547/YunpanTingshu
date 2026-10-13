package com.yunpan.tingshu.player.session

import com.yunpan.tingshu.player.PlayMode
import com.yunpan.tingshu.util.Clock
import com.yunpan.tingshu.util.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** 键值抽象：真机用 DataStore，测试用内存 fake。 */
interface SessionKv {
    suspend fun get(): String?
    suspend fun set(json: String?)
}

/**
 * 会话持久化（DataStore JSON；这里只做序列化/反序列化与读写，不绑定 Android，便于 JVM 单测）。
 * 不存会过期的直链 URL；启动/播放时按 fileId(CLOUD) 或 linkId+contentID(SHARE) 懒解析。
 */
class SessionStore(
    private val kv: SessionKv,
    private val clock: Clock = SystemClock
) {
    suspend fun load(): SavedSession? = kv.get()?.takeIf { it.isNotBlank() }?.let { decode(it) }

    suspend fun save(session: SavedSession) { kv.set(encode(session.copy(updatedAt = clock.nowMs()))) }

    suspend fun clear() { kv.set(null) }

    // ---------- JSON ----------

    fun encode(s: SavedSession): String {
        val o = JSONObject()
        o.put("source", s.source.name)
        s.accountId?.let { o.put("accountId", it) }
        s.linkId?.let { o.put("linkId", it) }
        s.passwd?.let { o.put("passwd", it) }
        s.shareName?.let { o.put("shareName", it) }
        o.put("albumTitle", s.albumTitle)
        s.rootToken?.let { o.put("rootToken", it) }
        o.put("index", s.index)
        o.put("positionMs", s.positionMs)
        o.put("playMode", s.playMode.name)
        o.put("speed", s.speed)
        s.sleepConfig?.let { o.put("sleepConfig", it) }
        o.put("updatedAt", s.updatedAt)
        val arr = JSONArray()
        for (t in s.tracks) {
            val to = JSONObject()
            to.put("fileId", t.fileId)
            t.childToken?.let { to.put("childToken", it) }
            to.put("name", t.name)
            to.put("albumPath", JSONArray(t.albumPath))
            t.audioExt?.let { to.put("audioExt", it) }
            to.put("durationSec", t.durationSec)
            to.put("isHls", t.isHls)
            t.parentToken?.let { to.put("parentToken", it) }
            arr.put(to)
        }
        o.put("tracks", arr)
        return o.toString()
    }

    fun decode(json: String): SavedSession? = try {
        val o = JSONObject(json)
        val tracks = ArrayList<SessionTrack>()
        val arr = o.optJSONArray("tracks")
        if (arr != null) for (i in 0 until arr.length()) {
            val to = arr.getJSONObject(i)
            tracks.add(
                SessionTrack(
                    fileId = to.optString("fileId"),
                    childToken = if (to.isNull("childToken")) null else to.optString("childToken"),
                    name = to.optString("name"),
                    albumPath = buildList {
                        val ap = to.optJSONArray("albumPath")
                        if (ap != null) for (j in 0 until ap.length()) add(ap.getString(j))
                    },
                    audioExt = if (to.isNull("audioExt")) null else to.optString("audioExt"),
                    durationSec = to.optLong("durationSec", 0L),
                    isHls = to.optBoolean("isHls", false),
                    parentToken = if (to.isNull("parentToken")) null else to.optString("parentToken")
                )
            )
        }
        SavedSession(
            source = SessionSource.valueOf(o.optString("source", "CLOUD")),
            accountId = if (o.isNull("accountId")) null else o.optString("accountId"),
            linkId = if (o.isNull("linkId")) null else o.optString("linkId"),
            passwd = if (o.isNull("passwd")) null else o.optString("passwd"),
            shareName = if (o.isNull("shareName")) null else o.optString("shareName"),
            albumTitle = o.optString("albumTitle", ""),
            rootToken = if (o.isNull("rootToken")) null else o.optString("rootToken"),
            tracks = tracks,
            index = o.optInt("index", 0),
            positionMs = o.optLong("positionMs", 0L),
            playMode = runCatching { PlayMode.valueOf(o.optString("playMode")) }.getOrDefault(PlayMode.SEQUENTIAL),
            speed = if (o.isNull("speed")) 1f else o.optDouble("speed", 1.0).toFloat(),
            sleepConfig = if (o.isNull("sleepConfig")) null else o.optString("sleepConfig"),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    } catch (_: Exception) {
        null
    }
}
