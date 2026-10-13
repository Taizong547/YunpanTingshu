package com.yunpan.tingshu.cloud139

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import com.yunpan.tingshu.util.TimeFmt
import org.json.JSONArray
import org.junit.Test

class Cloud139SignerTest {

    @Test
    fun `all 318 golden sign vectors match byte-for-byte`() {
        val arr = JSONArray(Golden.read("sign_vectors.json"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val body = o.getString("body")
            val ts = o.getString("ts")
            val rand = o.getString("rand")
            val expected = o.getString("sign")
            val actual = Cloud139Signer.sign(body, ts, rand)
            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun `random 200 bodies are deterministic and uppercase hex32`() {
        val rnd = java.util.Random(42)
        val chars = "abc中文①😀 !~*'()%-_."
        repeat(200) {
            val len = rnd.nextInt(60)
            val sb = StringBuilder()
            repeat(len) { sb.append(chars[rnd.nextInt(chars.length)]) }
            val body = sb.toString()
            val ts = "2026-10-04 09:36:18"
            val rand = "f6g55F4jF5nYcX4L"
            val s1 = Cloud139Signer.sign(body, ts, rand)
            val s2 = Cloud139Signer.sign(body, ts, rand)
            assertThat(s1).isEqualTo(s2)
            assertThat(s1).matches("^[A-F0-9]{32}$")
        }
    }

    @Test
    fun `empty body signs deterministically`() {
        val s = Cloud139Signer.sign("", "2026-01-01 00:00:00", "0123456789abcdef")
        assertThat(s).matches("^[A-F0-9]{32}$")
        assertThat(Cloud139Signer.sign("", "2026-01-01 00:00:00", "0123456789abcdef")).isEqualTo(s)
    }

    @Test
    fun `east8 timestamp format`() {
        val s = TimeFmt.east8(System.currentTimeMillis())
        assertThat(s).matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")
    }
}
