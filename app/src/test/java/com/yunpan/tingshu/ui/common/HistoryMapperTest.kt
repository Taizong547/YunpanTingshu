package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.data.model.FileSource
import org.junit.Test

class HistoryMapperTest {

    private fun history(
        source: String = "CLOUD139",
        positionMs: Long = 60_000,
        durationMs: Long = 300_000
    ) = HistoryEntity(
        fileId = "f1",
        accountId = "acc",
        source = source,
        folderId = "folderRoot",
        folderName = "老梁评书",
        fileName = "第01回.mp3",
        audioExt = "mp3",
        thumbnailUrl = "",
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = 1_700_000_000_000L,
        finished = false
    )

    @Test
    fun `toResumeFile preserves cloud fields`() {
        val f = HistoryMapper.toResumeFile(history())
        assertThat(f.id).isEqualTo("f1")
        assertThat(f.name).isEqualTo("第01回.mp3")
        assertThat(f.isDir).isFalse()
        assertThat(f.audioExt).isEqualTo("mp3")
        assertThat(f.source).isEqualTo(FileSource.CLOUD139)
        assertThat(f.isHls).isFalse()
        assertThat(f.albumPath).containsExactly("老梁评书")
        assertThat(f.durationSec).isEqualTo(300)
    }

    @Test
    fun `toResumeFile marks share as hls`() {
        val f = HistoryMapper.toResumeFile(history(source = "SHARE"))
        assertThat(f.source).isEqualTo(FileSource.SHARE)
        assertThat(f.isHls).isTrue()
    }

    @Test
    fun `unknown source falls back to cloud`() {
        val f = HistoryMapper.toResumeFile(history(source = "WEIRD"))
        assertThat(f.source).isEqualTo(FileSource.CLOUD139)
    }

    @Test
    fun `progress percent clamped`() {
        assertThat(HistoryMapper.progressPercent(history(positionMs = 150_000, durationMs = 300_000)))
            .isEqualTo(50)
        assertThat(HistoryMapper.progressPercent(history(positionMs = 400_000, durationMs = 300_000)))
            .isEqualTo(100)
        assertThat(HistoryMapper.progressPercent(history(durationMs = 0))).isEqualTo(0)
    }
}
