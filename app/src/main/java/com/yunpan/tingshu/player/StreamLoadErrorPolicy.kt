package com.yunpan.tingshu.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * HLS 流加载错误策略（v1.3）。
 *
 * 背景：客户端曾把"分片边界的传输抖动"误判成"整链过期"——移动网络在分片边界出现传输失败
 * （高度疑似共享 OkHttp 协商 HTTP/2 的连接复用 RST/GOAWAY）时，默认策略有限重试后升级为致命错误，
 * 恢复逻辑又拿同一条直链反复整源 re-prepare，4 次内放弃并提示"直链过期"。
 *
 * 分级策略：
 * - 分片(.ts)错误：只重试该片最多 [maxSegmentRetries] 次、指数退避 —— 抖动可自愈，不升级致命错误。
 * - 播放列表(.m3u8)返回 401/403/404/410：判定鉴权/链接过期，返回 [C.TIME_UNSET] 不重试该清单，
 *   由播放层 [PlaybackEngine] 按 [isManifestAuthExpired] 分级整体重新取直链（重新 openShare）。
 * - 其余（Progressive 直链等）：沿用默认策略。
 */
class StreamLoadErrorPolicy(
    private val maxSegmentRetries: Int = DEFAULT_MAX_SEGMENT_RETRIES,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS
) : DefaultLoadErrorHandlingPolicy() {

    /** 清单最多重试 1 次（避免在过期直链上反复空转）；分片按 [maxSegmentRetries]。 */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = when (dataType) {
        C.DATA_TYPE_MANIFEST -> MIN_MANIFEST_RETRIES
        else -> maxSegmentRetries
    }

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): Long {
        // 清单鉴权过期：不重试（C.TIME_UNSET），交由上层整体重开。
        val uri = loadErrorInfo.loadEventInfo.dataSpec.uri.toString()
        if (isManifestRequest(uri) && isAuthHttpCode(loadErrorInfo.exception)) {
            return C.TIME_UNSET
        }
        // 分片/传输错误：指数退避，封顶。
        val attempt = loadErrorInfo.errorCount.coerceAtLeast(1)
        val backoff = if (attempt <= 1) baseBackoffMs else baseBackoffMs shl (attempt - 1).coerceAtMost(MAX_SHIFT)
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }

    companion object {
        /** 分片错误最大重试次数。 */
        const val DEFAULT_MAX_SEGMENT_RETRIES = 16
        const val MIN_MANIFEST_RETRIES = 1
        private const val DEFAULT_BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 8_000L
        private const val MAX_SHIFT = 8

        /** 鉴权/链接失效的 HTTP 码（播放列表维度）。 */
        private val AUTH_CODES = setOf(401, 403, 404, 410)

        fun isManifestUrl(url: String): Boolean =
            url.substringBefore('?').substringBefore('#')
                .endsWith(".m3u8", ignoreCase = true)

        private fun isManifestRequest(url: String): Boolean = isManifestUrl(url)

        /** 沿 cause 链找 HTTP 状态码是否落在鉴权/失效集合。 */
        fun isAuthHttpCode(t: Throwable?): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is HttpDataSource.InvalidResponseCodeException && cur.responseCode in AUTH_CODES) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }

        /**
         * 分级判定：播放列表(.m3u8)返回 401/403/404/410 ⇒ 鉴权/链接过期，
         * 应整体重新取直链（SHARE 重新 openShare / CLOUD139 重新 getPlayUrl），而非原地重试。
         */
        fun isManifestAuthExpired(t: Throwable?): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is HttpDataSource.InvalidResponseCodeException) {
                    val uri = cur.dataSpec?.uri?.toString().orEmpty()
                    if (cur.responseCode in AUTH_CODES && isManifestUrl(uri)) return true
                }
                cur = cur.cause
            }
            return false
        }
    }
}
