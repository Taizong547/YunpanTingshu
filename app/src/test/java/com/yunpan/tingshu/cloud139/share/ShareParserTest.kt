package com.yunpan.tingshu.cloud139.share

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import com.yunpan.tingshu.data.model.FileSource
import org.junit.Test

class ShareParserTest {

    private val repo = ShareRepository(okhttp3.OkHttpClient())

    @Test
    fun `bootstrap root lists one folder no audio`() {
        val listing = repo.parseListing(Golden.read("share/00_bootstrap_root.resp.json"), "root")
        assertThat(listing.nodNum).isEqualTo(1)
        assertThat(listing.items).hasSize(1)
        val folder = listing.items[0]
        assertThat(folder.isDir).isTrue()
        assertThat(folder.name).isEqualTo("2026老梁精选")
        assertThat(folder.source).isEqualTo(FileSource.SHARE)
        assertThat(folder.childToken).isEqualTo("FiJFJg_ErEfLwzMXkb_ZIRJNKGKjzqzdy")
    }

    @Test
    fun `root resp has folders and 38 audio with presentURL hls`() {
        val listing = repo.parseListing(Golden.read("share/02_getOutLinkInfoV6_root.resp.json"), "root")
        assertThat(listing.nodNum).isEqualTo(45)
        val folders = listing.items.filter { it.isDir }
        val audios = listing.items.filter { it.isAudio }
        assertThat(folders).hasSize(1)
        assertThat(audios).hasSize(38)
        val first = audios[0]
        assertThat(first.source).isEqualTo(FileSource.SHARE)
        assertThat(first.isHls).isTrue()
        assertThat(first.directUrl).startsWith("https://")
        assertThat(first.directUrl).contains(".m3u8")
        assertThat(first.audioExt).isEqualTo("mp3")
        assertThat(first.durationSec).isGreaterThan(0)
    }

    @Test
    fun `video coType 3 not collected as audio`() {
        val listing = repo.parseListing(Golden.read("share/02b_getOutLinkInfoV6_subfolder.resp.json"), "Fh6Z")
        // 子目录是 mp4(coType=3)，不应收为音频
        assertThat(listing.items.count { it.isAudio }).isEqualTo(0)
    }

    @Test
    fun `single mp3 entry parses duration and ext`() {
        // 03 文件是单条 coLst 条目，包一层 data
        val wrapped = "{\"resultCode\":\"0\",\"data\":{\"coLst\":[" +
            Golden.read("share/03_mp3_entry_with_presentURL.json").trim() + "]}}"
        val listing = repo.parseListing(wrapped, "X")
        assertThat(listing.items).hasSize(1)
        val a = listing.items[0]
        assertThat(a.name).contains(".mp3")
        assertThat(a.audioExt).isEqualTo("mp3")
        assertThat(a.durationSec).isEqualTo(5427L)
        assertThat(a.isHls).isTrue()
    }

    @Test
    fun `parseShareLink tolerants url and bare id`() {
        assertThat(repo.parseShareLink("https://yun.139.com/shareweb/#/w/i/2xTrMfreXj5lq")).isEqualTo("2xTrMfreXj5lq")
        assertThat(repo.parseShareLink("https://yun.139.com/shareweb/#/w/i/2xTrMfreXj5lq?x=1")).isEqualTo("2xTrMfreXj5lq")
        assertThat(repo.parseShareLink("2xTrMfreXj5lq")).isEqualTo("2xTrMfreXj5lq")
    }
}
