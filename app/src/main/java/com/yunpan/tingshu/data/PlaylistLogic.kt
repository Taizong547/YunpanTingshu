package com.yunpan.tingshu.data

/** 歌单相关纯逻辑（无 Android 依赖，便于单测）。 */
object PlaylistLogic {

    /** 歌单名校验：去掉首尾空白后非空，长度 <= 20。返回规范化名称，非法返回 null。 */
    fun normalizeName(raw: String?): String? {
        val n = raw?.trim().orEmpty()
        if (n.isEmpty()) return null
        return n.take(20)
    }
}
