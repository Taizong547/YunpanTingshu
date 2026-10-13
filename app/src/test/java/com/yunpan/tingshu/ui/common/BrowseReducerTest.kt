package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import org.junit.Test

class BrowseReducerTest {

    private fun folder(name: String, id: String = name): CloudFile =
        CloudFile(id, name, true, 0, 0, "", "/", null, FileSource.CLOUD139)

    private fun audio(name: String, id: String = name): CloudFile =
        CloudFile(id, name, false, 1024, 0, "", "/", "mp3", FileSource.CLOUD139)

    private fun videoLike(name: String): CloudFile =
        CloudFile(name, name, false, 1024, 0, "", "/", "mp4", FileSource.CLOUD139)

    @Test
    fun `folders come before audio and each naturally sorted`() {
        val items = listOf(
            audio("第10回.mp3"),
            folder("子2"),
            audio("第2回.mp3"),
            folder("子1"),
            videoLike("x.mp4")
        )
        val out = BrowseReducer.foldersFirst(items)
        assertThat(out.map { it.name }).containsExactly("子1", "子2", "第2回.mp3", "第10回.mp3").inOrder()
    }

    @Test
    fun `audioOnly excludes folders and non-audio`() {
        val items = listOf(folder("a"), audio("b.mp3"), videoLike("c.mp4"))
        assertThat(BrowseReducer.audioOnly(items).map { it.name }).containsExactly("b.mp3")
    }

    @Test
    fun `appendPage dedupes by id`() {
        val cur = listOf(folder("a", "1"), audio("b.mp3", "2"))
        val page = listOf(audio("b.mp3", "2"), audio("c.mp3", "3"))
        val out = BrowseReducer.appendPage(cur, page)
        assertThat(out.map { it.id }).containsExactly("1", "2", "3").inOrder()
    }

    @Test
    fun `toScannedTracks carries folder album path`() {
        val tracks = BrowseReducer.toScannedTracks("老梁", listOf(audio("第1回.mp3", "x")))
        assertThat(tracks).hasSize(1)
        assertThat(tracks[0].albumPath).containsExactly("老梁")
        assertThat(tracks[0].file.albumPath).containsExactly("老梁")
    }
}
