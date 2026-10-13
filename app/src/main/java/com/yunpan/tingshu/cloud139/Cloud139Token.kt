package com.yunpan.tingshu.cloud139

import com.yunpan.tingshu.util.Base64

/**
 * 解析 `Authorization`（对应 TS parseCloud139Authorization，逐字照 SPEC §3.1 / auth.ts:19-29）。
 *
 * 顺序严格是 `replace(/^Basic\s+/i,'').trim()`——replace 先于 trim，
 * 故 Basic **之前**的前导空白/换行不容忍（会令 base64 解码失败抛错，黄金向量有此用例）；
 * 尾部空白/换行被 trim 容忍；小写 basic 与 Basic 后多个空格可被正则吃掉。
 */
object Cloud139Token {

    data class Parsed(
        /** 去 Basic 前缀并 trim 后的 base64 原文（请求头发 `Basic <raw>`） */
        val rawToken: String,
        val account: String,
        val tokenInfo: String,
        val expireAtMs: Long
    )

    private val BASIC_PREFIX = Regex("^Basic\\s+", RegexOption.IGNORE_CASE)

    fun parse(authorization: String): Parsed {
        // 注意顺序：先 replace 后 trim（与 TS 逐字一致）
        val normalized = authorization.replace(BASIC_PREFIX, "").trim()
        val decoded = try {
            Base64.decodeToStringUtf8(normalized)
        } catch (e: Exception) {
            throw IllegalArgumentException("Authorization 令牌不是合法的 Base64", e)
        }
        val parts = decoded.split(":")
        if (parts.size < 3) {
            throw IllegalArgumentException("Authorization 令牌格式不完整")
        }
        val account = parts[1]
        val tokenInfo = parts.drop(2).joinToString(":")
        val tokenParts = tokenInfo.split("|")
        val expireAtMs = tokenParts.getOrNull(3)?.toLongOrNull() ?: 0L
        return Parsed(normalized, account, tokenInfo, expireAtMs)
    }
}
