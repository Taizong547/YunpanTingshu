package com.yunpan.tingshu.ui.login

import android.util.Base64

/**
 * 从抓到的 Authorization（请求头值或 headers map）规范化出可保存的 Basic 凭证。
 * 纯逻辑、不碰网络；base64 解码以参数注入，便于 JVM 单测（生产用 android.util.Base64）。
 *
 * 规则（见 ACCOUNT_ACQUISITION_SPEC §3.4 / §4）：
 *  - 整体 trim，容忍前后空白/换行；
 *  - 容忍带 “Authorization:” 前缀（忽略大小写）；
 *  - 容忍无前缀 / 小写 basic / Basic 后多空格；
 *  - 只接受 Basic（拒绝 Bearer / 其它 scheme）；
 *  - base64 解码后须含 “:”（mobile:手机号:… 结构），否则判无效。
 */
object AuthHeaderExtractor {

    /** 生产用解码：android.util.Base64。解码失败返回 null。 */
    val androidDecoder: (String) -> String? = { raw ->
        runCatching { String(Base64.decode(raw, Base64.DEFAULT)) }.getOrNull()
    }

    /**
     * @param raw 原始头值，例如 "Basic MTM4…=="，或带前缀 "Authorization: Basic …"
     * @param decoder base64 解码；默认用 android.util.Base64
     * @return 规范化后的 "Basic <token>"；无效返回 null
     */
    fun normalize(
        raw: String?,
        decoder: (String) -> String? = androidDecoder
    ): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.isEmpty()) return null

        // 剥离可能的 "Authorization:" 前缀（忽略大小写，容忍冒号后空格）
        val prefixRe = Regex("^Authorization\\s*:\\s*", RegexOption.IGNORE_CASE)
        s = prefixRe.replace(s, "").trim()

        // 折叠内部空白（含换行）为单空格
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null

        // 拆分 scheme 与 token；无空格视为无前缀，整串当 token
        val parts = s.split(" ", limit = 2)
        val scheme: String
        val token: String
        if (parts.size == 1) {
            scheme = "basic"
            token = parts[0]
        } else {
            scheme = parts[0]
            token = parts[1].trim()
        }

        // 仅接受 Basic（大小写归一）
        if (!scheme.equals("basic", ignoreCase = true)) return null
        if (token.isBlank()) return null

        // 结构校验：解码后须含 “:”
        val decoded = decoder(token) ?: return null
        if (!decoded.contains(":")) return null

        return "Basic $token"
    }

    /** 从 headers map 忽略大小写找 Authorization 后规范化。 */
    fun fromHeaders(
        headers: Map<String, String>?,
        decoder: (String) -> String? = androidDecoder
    ): String? {
        if (headers.isNullOrEmpty()) return null
        val raw = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
        return normalize(raw, decoder)
    }
}
