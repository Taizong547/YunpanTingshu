package com.yunpan.tingshu.ui.login

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthHeaderExtractorTest {

    /** JVM 用解码器（生产用 android.util.Base64）。 */
    private val decoder: (String) -> String? = { raw ->
        runCatching { String(java.util.Base64.getDecoder().decode(raw)) }.getOrNull()
    }

    /** 解码后形如 mobile:手机号:xxx|xxx|xxx|过期戳，含冒号。 */
    private val TOKEN = "MTM4MDAxMzgwMDA6eHh4eHh8eHh4fHh4eHwxMjM0NTY3ODkwMDAwMA=="

    private fun norm(raw: String?) = AuthHeaderExtractor.normalize(raw, decoder)

    @Test fun `standard basic passes through`() {
        assertThat(norm("Basic $TOKEN")).isEqualTo("Basic $TOKEN")
    }

    @Test fun `extra spaces after basic collapsed to single`() {
        assertThat(norm("Basic   $TOKEN")).isEqualTo("Basic $TOKEN")
    }

    @Test fun `missing basic prefix is added`() {
        assertThat(norm(TOKEN)).isEqualTo("Basic $TOKEN")
    }

    @Test fun `lowercase basic normalized to uppercase`() {
        assertThat(norm("basic $TOKEN")).isEqualTo("Basic $TOKEN")
    }

    @Test fun `surrounding whitespace and newlines trimmed`() {
        assertThat(norm("  Basic $TOKEN\n")).isEqualTo("Basic $TOKEN")
    }

    @Test fun `bearer rejected`() {
        assertThat(norm("Bearer eyJhbGciOiJIUzI1NiJ9.abc.def")).isNull()
    }

    @Test fun `invalid base64 rejected`() {
        assertThat(norm("Basic not_a_valid_base64!!!")).isNull()
    }

    @Test fun `base64 without colon structure rejected`() {
        // "abc" 的 base64 解码后无冒号
        val b64 = java.util.Base64.getEncoder().encodeToString("abc".toByteArray())
        assertThat(norm("Basic $b64")).isNull()
    }

    @Test fun `authorization prefix stripped`() {
        assertThat(norm("Authorization: Basic $TOKEN")).isEqualTo("Basic $TOKEN")
    }

    @Test fun `authorization prefix case-insensitive stripped`() {
        assertThat(norm("authorization: basic $TOKEN")).isEqualTo("Basic $TOKEN")
    }

    @Test fun `null and blank return null`() {
        assertThat(norm(null)).isNull()
        assertThat(norm("")).isNull()
        assertThat(norm("   ")).isNull()
    }

    @Test fun `from headers finds authorization case-insensitively`() {
        val headers = mapOf("Host" to "yun.139.com", "authorization" to "Basic $TOKEN")
        assertThat(AuthHeaderExtractor.fromHeaders(headers, decoder)).isEqualTo("Basic $TOKEN")
    }

    @Test fun `from headers missing returns null`() {
        assertThat(AuthHeaderExtractor.fromHeaders(emptyMap(), decoder)).isNull()
        assertThat(AuthHeaderExtractor.fromHeaders(mapOf("A" to "b"), decoder)).isNull()
    }
}
