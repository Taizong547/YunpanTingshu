package com.yunpan.tingshu.cloud139

import java.net.URI

/**
 * 个人云盘 URL 拼接（照 dirfilelist.ts:35-85 / SPEC §3.5）。
 * 与 TS cloud139PersonalUrl / cloud139PersonalUrls 逐字节一致；含 /hcy 兜底与去 /orchestration 重复。
 */
object Cloud139Urls {

    /** 新接口 parentId 归一：cloud139_root / 0 / 空 -> "/"。 */
    fun apiParentId(parentId: String?): String {
        val id = parentId ?: "/"
        return if (id == "cloud139_root" || id == "0" || id.isEmpty()) "/" else id
    }

    /** 老接口 parentId 归一：cloud139_root / / / 0 / 空 -> "root"。 */
    fun oldApiParentId(parentId: String?): String {
        val id = parentId ?: "root"
        return if (id == "cloud139_root" || id == "/" || id == "0" || id.isEmpty()) "root" else id
    }

    /** 主 URL（单候选）。 */
    fun personalUrl(host: String, endpoint: String): String {
        val base = host.trimEnd('/')
        if (endpoint.startsWith("/orchestration")) return base + endpoint
        val path = URI(base).path.trimEnd('/')
        if (path.isNotEmpty() && path != "/") return base + endpoint
        if (base.endsWith("/orchestration")) return base + endpoint
        if (base.contains("/orchestration/")) return base + endpoint
        return "$base/orchestration$endpoint"
    }

    /** 候选 URL 列表：主 URL + 去 /hcy 后的 fallback（去重）。 */
    fun personalUrls(host: String, endpoint: String): List<String> {
        val urls = LinkedHashMap<String, Boolean>()
        urls[personalUrl(host, endpoint)] = true
        val withoutHcy = host
            .replace(Regex("/hcy/orchestration/?$", RegexOption.IGNORE_CASE), "/orchestration")
            .replace(Regex("/hcy/?$", RegexOption.IGNORE_CASE), "")
        val fallback = personalUrl(withoutHcy, endpoint)
        urls[fallback] = true
        return urls.keys.toList()
    }
}
