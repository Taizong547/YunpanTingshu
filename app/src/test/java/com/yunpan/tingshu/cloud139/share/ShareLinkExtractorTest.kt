package com.yunpan.tingshu.cloud139.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkExtractorTest {

    @Test
    fun extracts_shareweb_url_and_passcode_from_real_paste() {
        val text = """链接: https://yun.139.com/shareweb/#/w/i/2ygbbRFet7bws
提取码: s1ak
/*n91NqqAaoVKq:/
复制内容打开移动云盘PC客户端，操作更方便哦"""
        val r = ShareLinkExtractor.parse(text)
        assertEquals("https://yun.139.com/shareweb/#/w/i/2ygbbRFet7bws", r.link)
        assertEquals("s1ak", r.passcode)
    }

    @Test
    fun extracts_plain_url_and_labeled_passcode() {
        val text = "复制内容打开移动云盘PC客户端 https://yun.139.com/w/i/AbC123 提取码：x9z8"
        val r = ShareLinkExtractor.parse(text)
        assertEquals("https://yun.139.com/w/i/AbC123", r.link)
        assertEquals("x9z8", r.passcode)
    }

    @Test
    fun adds_https_when_protocol_missing() {
        val r = ShareLinkExtractor.parse("看看这个 yun.139.com/w/i/xyz789 不错吧")
        assertEquals("https://yun.139.com/w/i/xyz789", r.link)
        assertNull(r.passcode)
    }

    @Test
    fun returns_null_for_garbage() {
        assertNull(ShareLinkExtractor.extract("今天天气不错"))
        assertNull(ShareLinkExtractor.extract(""))
        assertNull(ShareLinkExtractor.extract(null))
    }

    @Test
    fun https_not_treated_as_passcode() {
        val r = ShareLinkExtractor.parse("链接: https://yun.139.com/w/i/abc123")
        assertEquals("https://yun.139.com/w/i/abc123", r.link)
        assertNull(r.passcode)
    }

    @Test
    fun plain_url_passes_through() {
        assertEquals(
            "https://yun.139.com/w/i/single",
            ShareLinkExtractor.extract("https://yun.139.com/w/i/single")
        )
    }
}
