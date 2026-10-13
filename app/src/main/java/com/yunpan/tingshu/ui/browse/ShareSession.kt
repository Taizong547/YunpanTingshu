package com.yunpan.tingshu.ui.browse

/**
 * 待进入浏览页的分享会话。我的页成功 openShare 后 [offer]，浏览页进入时 [consume]。
 * 进程内单例（由 ServiceLocator 持有），避免底部 Tab 目的地传参的复杂性。
 */
class ShareSession {

    data class Pending(
        val linkId: String,
        val passwd: String,
        val title: String
    )

    @Volatile
    private var pending: Pending? = null

    fun offer(p: Pending) { pending = p }

    /** 取出并清除待进入的分享（同一分享只进入一次）。 */
    fun consume(): Pending? {
        val p = pending
        pending = null
        return p
    }

    fun peek(): Pending? = pending
}
