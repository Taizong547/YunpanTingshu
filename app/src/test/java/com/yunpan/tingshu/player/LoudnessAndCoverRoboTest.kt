package com.yunpan.tingshu.player

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.cover.ArtworkRef
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.MediaSpec
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoudnessAndCoverRoboTest {

    private fun file() = CloudFile(
        id = "f1", name = "第一集.mp3", isDir = false, size = 0, updatedAtMs = 0,
        thumbnailUrl = "", parentId = "p", audioExt = "mp3",
        source = FileSource.CLOUD139, albumPath = listOf("专辑X")
    )

    private fun buildSource(art: ArtworkRef?) =
        MediaSourceBuilder.build(
            okhttp3.OkHttpClient(),
            MediaSpec("https://cdn/x.mp3", mapOf("UA" to "agent"), isHls = false),
            file(), "专辑X", art
        ).mediaItem.mediaMetadata

    @Test fun localCoverSetsFileUri() {
        val tmp = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "cover.jpg")
        val meta = buildSource(ArtworkRef.Local(tmp.absolutePath))
        assertThat(meta.artworkUri?.scheme).isEqualTo("file")
        assertThat(meta.artworkUri?.path).isEqualTo(tmp.absolutePath)
    }

    @Test fun remoteCoverSetsHttpUri() {
        val meta = buildSource(ArtworkRef.Remote("https://cdn/cover.jpg", emptyMap()))
        assertThat(meta.artworkUri?.toString()).isEqualTo("https://cdn/cover.jpg")
    }

    @Test fun nullCoverSetsNoArtwork() {
        val meta = buildSource(null)
        assertThat(meta.artworkUri).isNull()
    }

    @Test fun metadataTitleAndArtist() {
        val meta = buildSource(null)
        assertThat(meta.title).isEqualTo("第一集.mp3")
        assertThat(meta.artist).isEqualTo("专辑X")
        assertThat(meta.albumTitle).isEqualTo("专辑X")
    }

    @Test fun hlsSourceAlsoCarriesMetadata() {
        val src = MediaSourceBuilder.build(
            okhttp3.OkHttpClient(),
            MediaSpec("https://cdn/live.m3u8", emptyMap(), isHls = true),
            file(), "专辑X", ArtworkRef.Remote("https://cdn/c.jpg")
        )
        assertThat(src.mediaItem.mediaMetadata.title).isEqualTo("第一集.mp3")
        assertThat(src.mediaItem.mediaMetadata.artworkUri?.toString()).isEqualTo("https://cdn/c.jpg")
    }
}
