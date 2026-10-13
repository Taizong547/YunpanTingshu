package com.yunpan.tingshu.cloud139

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import org.junit.Test

class Cloud139ParserTest {

    @Test
    fun `list items maps fields and fallback names`() {
        val page = Cloud139Parser.listFromResponse(Golden.read("samples/list_items.json"), "/", "")
        assertThat(page.nextCursor).isEqualTo("cursor-page-1")
        assertThat(page.items).hasSize(3)

        val folder1 = page.items[0]
        assertThat(folder1.id).isEqualTo("folder-001")
        assertThat(folder1.name).isEqualTo("三体全集")
        assertThat(folder1.isDir).isTrue()

        val audio = page.items[1]
        assertThat(audio.id).isEqualTo("audio-001")
        assertThat(audio.name).isEqualTo("第一章 红岸往事.mp3")
        assertThat(audio.isDir).isFalse()
        assertThat(audio.audioExt).isEqualTo("mp3")
        assertThat(audio.size).isEqualTo(15728640L)
        assertThat(audio.thumbnailUrl).isEqualTo("https://example.com/thumb/small/audio-001.jpg")
        assertThat(audio.updatedAtMs).isGreaterThan(0)

        val folder2 = page.items[2]
        assertThat(folder2.id).isEqualTo("folder-002")
        assertThat(folder2.isDir).isTrue() // catalogId 存在即文件夹
    }

    @Test
    fun `old interface contentList concatenated`() {
        val page = Cloud139Parser.listFromResponse(Golden.read("samples/list_content_list_old.json"), "root", "")
        assertThat(page.items).hasSize(2)
        assertThat(page.items[0].id).isEqualTo("old-audio-001")
        assertThat(page.items[0].name).isEqualTo("射雕英雄传 第01回.m4b")
        assertThat(page.items[0].audioExt).isEqualTo("m4b")
        // 14 位时间格式可解析
        assertThat(page.items[0].updatedAtMs).isGreaterThan(0)
    }

    @Test
    fun `multi fallback uses fileList`() {
        val page = Cloud139Parser.listFromResponse(Golden.read("samples/list_multi_fallback.json"), "/", "")
        assertThat(page.items.map { it.name })
            .containsExactly("多兜底音频.mp3", "多兜底有声书.m4b", "多兜底无损.flac")
    }

    @Test
    fun `audio extension whitelist filters`() {
        val page = Cloud139Parser.listFromResponse(Golden.read("samples/list_audio_exts.json"), "/", "")
        val audios = page.items.filter { it.isAudio }
        assertThat(audios.map { it.audioExt })
            .containsExactly("mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav", "wma", "amr")
        // txt / jpg 不在白名单
        assertThat(page.items.count { it.isAudio }).isEqualTo(10)
    }

    @Test
    fun `pagination cursor equality treated as empty`() {
        val p1 = Cloud139Parser.listFromResponse(Golden.read("samples/list_page1.json"), "/", "")
        assertThat(p1.nextCursor).isEqualTo("next-page-cursor")
        val p2 = Cloud139Parser.listFromResponse(Golden.read("samples/list_page2.json"), "/", "next-page-cursor")
        assertThat(p2.nextCursor).isEmpty()
    }

    @Test
    fun `time formats`() {
        // ISO
        assertThat(Cloud139Parser.parseTime("2026-07-13T10:00:00.000+08:00")).isGreaterThan(0)
        // 14 位
        assertThat(Cloud139Parser.parseTime("20260713225311")).isGreaterThan(0)
        // 空
        assertThat(Cloud139Parser.parseTime("")).isEqualTo(0)
    }
}
