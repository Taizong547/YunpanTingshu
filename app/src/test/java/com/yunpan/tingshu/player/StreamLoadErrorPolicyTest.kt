package com.yunpan.tingshu.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * v1.3 回归：StreamLoadErrorPolicy 分级策略。
 * 核心诉求：分片(.ts)错误只重试该片（不升级致命）；只有清单(.m3u8) 401/403/404/410 才判定"鉴权过期"。
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StreamLoadErrorPolicyTest {

    private val policy = StreamLoadErrorPolicy()

    private fun manifestSpec() =
        DataSpec(android.net.Uri.parse("https://c/d/v3/hls/vodId/audio/index.m3u8"))
    private fun masterSpec() =
        DataSpec(android.net.Uri.parse("https://c/d/v3/hls/vodId/playlist.m3u8"))
    private fun segmentSpec() =
        DataSpec(android.net.Uri.parse("https://c/d/v3/hls/vodId/audio/index12.ts"))

    private fun http(code: Int, spec: DataSpec) =
        HttpDataSource.InvalidResponseCodeException(
            code, "resp-$code", null, emptyMap(), spec, byteArrayOf()
        )

    // ---------- 分级判定 ----------

    @Test fun `media playlist 401 判定为清单鉴权过期`() {
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(http(401, manifestSpec()))).isTrue()
    }

    @Test fun `master playlist 403 判定为清单鉴权过期`() {
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(http(403, masterSpec()))).isTrue()
    }

    @Test fun `playlist 404 与 410 判定为清单过期`() {
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(http(404, manifestSpec()))).isTrue()
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(http(410, manifestSpec()))).isTrue()
    }

    @Test fun `playlist 非鉴权码 500 不判定为清单过期`() {
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(http(500, manifestSpec()))).isFalse()
    }

    @Test fun `分片 ts 403 不判定为清单鉴权过期`() {
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(http(403, segmentSpec()))).isFalse()
    }

    @Test fun `cause 链深处的 401 也能识别`() {
        val deep = IOException("wrapped", IOException("inner", http(401, manifestSpec())))
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(deep)).isTrue()
    }

    @Test fun `普通 IO 错误不判定为鉴权过期`() {
        assertThat(StreamLoadErrorPolicy.isManifestAuthExpired(IOException("network"))).isFalse()
    }

    // ---------- 重试策略 ----------

    @Test fun `清单最小重试次数为 1`() {
        assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST)).isEqualTo(1)
    }

    @Test fun `分片最小重试次数为 16`() {
        assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA)).isEqualTo(16)
        assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_INITIALIZATION)).isEqualTo(16)
    }

    @Test fun `清单 401 返回不重试（TIME_UNSET）`() {
        val info = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(1, manifestSpec(), 0),
            MediaLoadData(C.DATA_TYPE_MANIFEST),
            http(401, manifestSpec()), 1
        )
        assertThat(policy.getRetryDelayMsFor(info)).isEqualTo(C.TIME_UNSET)
    }

    @Test fun `分片传输错误按指数退避`() {
        val io = IOException("boom")
        val spec = segmentSpec()
        val info1 = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(1, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), io, 1
        )
        assertThat(policy.getRetryDelayMsFor(info1)).isEqualTo(500)
        val info3 = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(3, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), io, 3
        )
        assertThat(policy.getRetryDelayMsFor(info3)).isEqualTo(2000)
    }

    @Test fun `分片退避封顶 8 秒`() {
        val io = IOException("boom")
        val spec = segmentSpec()
        val info10 = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(10, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), io, 10
        )
        assertThat(policy.getRetryDelayMsFor(info10)).isAtMost(8_000L)
    }
}
