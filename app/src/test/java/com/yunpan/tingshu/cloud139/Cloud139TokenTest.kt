package com.yunpan.tingshu.cloud139

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import com.yunpan.tingshu.data.Accounts
import org.json.JSONArray
import org.junit.Test

class Cloud139TokenTest {

    @Test
    fun `all 13 token vectors match`() {
        val arr = JSONArray(Golden.read("token_vectors.json"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val input = o.getString("input")
            val expectError = o.optBoolean("expectError", false)
            if (expectError) {
                val expectedErr = o.optString("error", "")
                try {
                    Cloud139Token.parse(input)
                    throw AssertionError("vector #$i should have thrown: input=$input")
                } catch (e: IllegalArgumentException) {
                    if (expectedErr == "Authorization 令牌格式不完整") {
                        assertThat(e.message).contains("Authorization 令牌格式不完整")
                    }
                    // 其它错误（如 base64 非法）只要抛了即可
                }
            } else {
                val p = Cloud139Token.parse(input)
                assertThat(p.account).isEqualTo(o.getString("account"))
                assertThat(p.tokenInfo).isEqualTo(o.getString("tokenInfo"))
                assertThat(p.expireAtMs).isEqualTo(o.getLong("expireTime"))
            }
        }
    }

    @Test
    fun `leading whitespace before Basic fails in strict parser`() {
        // 向量 11：replace 先于 trim，前导空白令 base64 解码失败
        val input = "  Basic WDoxMzQ1NTU1NjY2NjprMXxrMnxhOmI6aW5zaWRlfDE4OTk5OTk5OTk5OTZ8ZW5k"
        try {
            Cloud139Token.parse(input)
            throw AssertionError("should throw")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `AccountStore tolerant pre-trim path recovers leading whitespace`() {
        // UI 宽容：addFromAuthorization 先对整串 trim()，故前导空白被去掉后能解析成功
        val input = "  Basic WDoxMzQ1NTU1NjY2NjprMXxrMnxhOmI6aW5zaWRlfDE4OTk5OTk5OTk5OTZ8ZW5k \n "
        val account = Accounts.fromAuthorization(input, 1700000000000L)
        assertThat(account.phone).isEqualTo("13455556666")
        assertThat(account.expireAtMs).isEqualTo(1899999999996L)
        assertThat(account.rawToken).isEqualTo("WDoxMzQ1NTU1NjY2NjprMXxrMnxhOmI6aW5zaWRlfDE4OTk5OTk5OTk5OTZ8ZW5k")
    }

    @Test
    fun `malformed input throws Chinese message`() {
        try {
            Cloud139Token.parse("")
            throw AssertionError("should throw")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Authorization 令牌格式不完整")
        }
    }
}
