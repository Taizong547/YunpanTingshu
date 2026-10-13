package com.yunpan.tingshu.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.MediaSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 带头播放 / MediaSource 选择验证（Robolectric, sdk=33）。
 *
 * 说明：Robolectric 在 4G 机器全量测试下，ExoPlayer 主 looper 泵取不稳定，
 * 故按 SPEC §7 的降级策略把“带头数据源”断言下沉为确定性的 OkHttpDataSource GET：
 *   带自定义默认头(UA/Referer/Origin)的数据源能 200 拉到音频字节，且服务端确实收到这些头；
 * 另用本地 WAV 文件让 ExoPlayer(ProgressiveMediaSource) prepare 到 READY（无网络线程，稳定）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackEngineMediaTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun wavBytes(): ByteArray =
        PlaybackEngineMediaTest::class.java.classLoader!!
            .getResource("sample_tone.wav")!!.readBytes()

    /** 确定性：带头 OkHttpDataSource 能拉到音频字节，服务端收到 UA/Referer/Origin。 */
    @Test fun okHttpDataSourceWithCustomHeadersFetchesAndServerReceivesHeaders() {
        val wav = wavBytes()
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(wav))
                .setHeader("Content-Type", "audio/wav")
        )
        val url = server.url("/audio/tone.wav").toString()

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120",
            "Referer" to "https://yun.139.com/",
            "Origin" to "https://yun.139.com"
        )
        val factory = OkHttpDataSource.Factory(OkHttpClient()).setDefaultRequestProperties(headers)
        val ds = factory.createDataSource()
        val stream = androidx.media3.datasource.DataSpec(android.net.Uri.parse(url))
        ds.open(stream)
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var guard = 0
        while (guard++ < 1000) {
            val n = ds.read(tmp, 0, tmp.size)
            if (n == -1) break
            if (n > 0) buf.write(tmp, 0, n)
        }
        ds.close()
        val body = buf.toByteArray()
        assertThat(body.size).isGreaterThan(1000)

        val req = server.takeRequest(2, TimeUnit.SECONDS)
        checkNotNull(req)
        assertThat(req.getHeader("User-Agent")).contains("Chrome/120")
        assertThat(req.getHeader("Referer")).isEqualTo("https://yun.139.com/")
        assertThat(req.getHeader("Origin")).isEqualTo("https://yun.139.com")
    }

    /** ExoPlayer + ProgressiveMediaSource 能 prepare 到 READY（本地 WAV，稳定）。 */
    @Test fun exoPlayerPreparesLocalWavToReady() {
        val tmp = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "tone.wav")
        tmp.writeBytes(wavBytes())
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        val source = ProgressiveMediaSource.Factory(
            androidx.media3.datasource.DefaultDataSourceFactory(
                ApplicationProvider.getApplicationContext<android.content.Context>()
            )
        ).createMediaSource(MediaItem.fromUri(tmp.toURI().toString()))
        player.setMediaSource(source)
        player.prepare()

        val mainLooper = android.os.Looper.getMainLooper()
        var state = player.playbackState
        val deadline = System.currentTimeMillis() + 15_000
        while (state != Player.STATE_READY && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            Thread.sleep(80)
            state = player.playbackState
        }
        assertThat(state).isEqualTo(Player.STATE_READY)
        player.release()
    }

    @Test fun mediaSourceBuilderSelectsHlsVsProgressive() {
        val okHttp = OkHttpClient()
        val file = CloudFile(
            id = "x", name = "x.m3u8", isDir = false, size = 0, updatedAtMs = 0,
            thumbnailUrl = "", parentId = "/", audioExt = "m4a", source = FileSource.SHARE
        )
        val hls = MediaSourceBuilder.build(
            okHttp, MediaSpec("https://cdn/x.m3u8", emptyMap(), isHls = true), file, "专辑"
        )
        assertThat(hls).isInstanceOf(HlsMediaSource::class.java)

        val prog = MediaSourceBuilder.build(
            okHttp, MediaSpec("https://cdn/x.mp3", mapOf("UA" to "u"), isHls = false), file, "专辑"
        )
        assertThat(prog).isInstanceOf(ProgressiveMediaSource::class.java)
    }
}
