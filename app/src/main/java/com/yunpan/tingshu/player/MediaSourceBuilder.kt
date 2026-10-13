package com.yunpan.tingshu.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.yunpan.tingshu.data.cover.ArtworkRef
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.MediaSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 按 [MediaSpec.isHls] 选择 MediaSource（HLS / Progressive），两种源都带 MediaMetadata：
 *  - title=文件名、artist/albumTitle=专辑名；
 *  - artwork：Local(path)→文件 uri；Remote(url,*)→http uri（系统通知可能不带自定义头，best-effort）；null 不设。
 *
 * v1.3：流媒体客户端强制 HTTP/1.1 + retryOnConnectionFailure + 流式超时，
 * 并挂载 [StreamLoadErrorPolicy]（分片级重试 vs 清单鉴权过期分级）。
 * 目的：消除"共享 OkHttp 连接复用（HTTP/2 RST/GOAWAY）导致分片边界传输失败被误判为整链过期"的 10 秒断播。
 * 注意：这里只派生专用客户端，不改动全局 okHttp（业务请求仍需 HTTP/2 复用与签名头）。
 */
internal object MediaSourceBuilder {

    fun build(
        okHttp: OkHttpClient,
        spec: MediaSpec,
        file: CloudFile,
        albumTitle: String,
        artwork: ArtworkRef? = null
    ): MediaSource {
        val streamClient = okHttp.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(streamClient)
            .setDefaultRequestProperties(spec.headers)

        val artist = albumTitle.ifEmpty { file.albumPath.lastOrNull() ?: "" }
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(file.name)
            .setArtist(artist)
            .setAlbumTitle(artist)
        when (artwork) {
            is ArtworkRef.Local -> metaBuilder.setArtworkUri(Uri.fromFile(File(artwork.path)))
            is ArtworkRef.Remote -> runCatching { metaBuilder.setArtworkUri(Uri.parse(artwork.url)) }
            null -> {}
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(file.id)
            .setUri(spec.uri)
            .setMediaMetadata(metaBuilder.build())
            .build()

        val policy = StreamLoadErrorPolicy()
        return if (spec.isHls) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(mediaItem)
        }
    }
}
