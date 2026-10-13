package com.yunpan.tingshu.cloud139

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import org.json.JSONArray
import org.junit.Test

class Cloud139UrlsTest {

    @Test
    fun `all 10 url vectors match primary and candidate order`() {
        val arr = JSONArray(Golden.read("url_vectors.json"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val host = o.getString("host")
            val endpoint = o.getString("endpoint")
            val primary = Cloud139Urls.personalUrl(host, endpoint)
            assertThat(primary).isEqualTo(o.getString("primary"))
            val candidates = Cloud139Urls.personalUrls(host, endpoint)
            val expectedCandidates = o.getJSONArray("candidates")
            assertThat(candidates)
                .containsExactlyElementsIn(expectedCandidates.toList().map { it.toString() })
                .inOrder()
        }
    }

    @Test
    fun `parentId normalization new and old`() {
        assertThat(Cloud139Urls.apiParentId("")).isEqualTo("/")
        assertThat(Cloud139Urls.apiParentId("0")).isEqualTo("/")
        assertThat(Cloud139Urls.apiParentId("cloud139_root")).isEqualTo("/")
        assertThat(Cloud139Urls.apiParentId("abc")).isEqualTo("abc")
        assertThat(Cloud139Urls.oldApiParentId("/")).isEqualTo("root")
        assertThat(Cloud139Urls.oldApiParentId("0")).isEqualTo("root")
        assertThat(Cloud139Urls.oldApiParentId("abc")).isEqualTo("abc")
    }
}
