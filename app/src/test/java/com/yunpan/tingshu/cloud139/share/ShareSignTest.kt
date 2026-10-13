package com.yunpan.tingshu.cloud139.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareSignTest {

    @Test
    fun `captured vector recomputes exact sign`() {
        // share-api.md §5 / sign.js 闭环验证：
        val plain =
            "{\"getOutLinkInfoReq\":{\"account\":\"\",\"linkID\":\"2xTrMfreXj5lq\",\"passwd\":\"\",\"caSrt\":0,\"coSrt\":0,\"srtDr\":1,\"bNum\":1,\"pCaID\":\"Fm48D-UmB_2QeJ279fcdFOYBUUtJLAdnk/FiJFJg_ErEfLwzMXkb_ZIRJNKGKjzqzdy\",\"eNum\":200}}"
        val timeStr = "2026-10-04 09:36:18"
        val rand = "f6g55F4jF5nYcX4L"
        val sign = ShareSign.sign(plain, timeStr, rand)
        assertThat(sign).isEqualTo("3DB1D45BEF4FA8703161384B81B4D691")
    }

    @Test
    fun `header value format time,rand,sign`() {
        val (_, _, header) = ShareSign.build("{\"a\":1}", "2026-10-04 09:36:18", "f6g55F4jF5nYcX4L")
        assertThat(header).startsWith("2026-10-04 09:36:18,f6g55F4jF5nYcX4L,")
    }
}
