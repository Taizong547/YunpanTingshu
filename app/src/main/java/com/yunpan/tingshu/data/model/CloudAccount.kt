package com.yunpan.tingshu.data.model

/**
 * 一个已登录的个人云盘 139 账号（SPEC §4）。
 * @param id          账号稳定 id（phone 或 "cloud139_"+hash）
 * @param phone       account（手机号），可能为空
 * @param rawToken    base64 原文（不含 "Basic " 前缀），请求头发 `Authorization: Basic <rawToken>`
 * @param expireAtMs  过期 epoch ms；0 = 未知
 * @param displayName 展示名
 * @param addedAtMs   加入时间 epoch ms
 */
data class CloudAccount(
    val id: String,
    val phone: String,
    val rawToken: String,
    val expireAtMs: Long,
    val displayName: String,
    val addedAtMs: Long
) {
    val isExpired: Boolean get() = expireAtMs > 0 && expireAtMs <= System.currentTimeMillis()
}
