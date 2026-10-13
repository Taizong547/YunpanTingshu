package com.yunpan.tingshu.ui.common

/**
 * 面包屑（路径层级）纯逻辑：相邻同名段去重 + 超长中间省略。
 * 不持有 Android 依赖，便于 JVM 单测。
 *
 * 背景：真机出现“视频下载内容 › 视频下载内容 › …”相邻重复段（分享根名与首个同名文件夹叠加）。
 */
object BreadcrumbUtils {

    /** 去除相邻同名段（忽略首尾空白后比较）；保留根段。空输入返回空。 */
    fun dedupe(names: List<String>): List<String> {
        val out = ArrayList<String>(names.size)
        for (raw in names) {
            val n = raw.trim()
            if (n.isEmpty()) continue
            val last = out.lastOrNull()
            if (last == n) continue // 相邻同名，丢弃后段
            out.add(n)
        }
        return out
    }

    /**
     * 超长面包屑中间省略：总段数不超过 [maxSegments] 原样返回；
     * 否则仅保留首段与末段，中间以 “…” 占位（渲染为 “A › … › Z”）。
     * @param maxSegments 允许原样显示的最大段数（建议 4）
     */
    fun ellipsizeMiddle(names: List<String>, maxSegments: Int = 4): List<String> {
        if (names.size <= maxSegments) return names
        if (names.size <= 2) return names
        return listOf(names.first(), "…", names.last())
    }

    /** 一键：先去重再省略。 */
    fun normalize(names: List<String>, maxSegments: Int = 4): List<String> =
        ellipsizeMiddle(dedupe(names), maxSegments)
}
