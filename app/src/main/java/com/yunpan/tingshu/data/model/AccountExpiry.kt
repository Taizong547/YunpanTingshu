package com.yunpan.tingshu.data.model

/**
 * 账号有效期评估（纯逻辑，可单测）。
 * 139 Authorization 无 refresh token，过期后只能重新登录；
 * 本地歌单/书架/历史存在 Room，不会因重新登录丢失。
 */
object AccountExpiry {

    private const val WARN_MS = 24L * 3600 * 1000   // 24h 内温和提示
    private const val NEVER = 0L

    sealed class State {
        /** 未知有效期（token 未携带到期时间）。 */
        object Unknown : State()
        object Valid : State()
        /** 24h 内即将到期，剩余小时数。 */
        data class ExpiringSoon(val hoursLeft: Long) : State()
        object Expired : State()
    }

    fun evaluate(expireAtMs: Long, nowMs: Long): State {
        if (expireAtMs <= NEVER) return State.Unknown
        val diff = expireAtMs - nowMs
        if (diff <= 0L) return State.Expired
        if (diff <= WARN_MS) return State.ExpiringSoon(diff / 3600_000L + 1L)
        return State.Valid
    }
}
