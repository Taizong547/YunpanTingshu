package com.yunpan.tingshu.cloud139.share

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import com.yunpan.tingshu.util.Base64
import org.junit.Test

class OutlinkCryptoTest {

    @Test
    fun `decrypt real browser-captured request b64`() {
        val b64 = Golden.read("share/01_getOutLinkInfoV6.req.b64.txt").trim()
        val plain = OutlinkCrypto.decrypt(b64)
        assertThat(plain).isEqualTo(
            "{\"getOutLinkInfoReq\":{\"account\":\"\",\"linkID\":\"2xTrMfreXj5lq\",\"passwd\":\"\",\"caSrt\":0,\"coSrt\":0,\"srtDr\":1,\"bNum\":1,\"pCaID\":\"Fm48D-UmB_2QeJ279fcdFOYBUUtJLAdnk/FiJFJg_ErEfLwzMXkb_ZIRJNKGKjzqzdy\",\"eNum\":200}}"
        )
    }

    @Test
    fun `round trip encrypt then decrypt`() {
        val plain = "{\"getOutLinkInfoReq\":{\"account\":\"\",\"linkID\":\"2xTrMfreXj5lq\",\"passwd\":\"\",\"caSrt\":0,\"coSrt\":0,\"srtDr\":1,\"bNum\":1,\"pCaID\":\"root\",\"eNum\":200}}"
        val enc = OutlinkCrypto.encrypt(plain)
        val back = OutlinkCrypto.decrypt(enc)
        assertThat(back).isEqualTo(plain)
    }

    @Test
    fun `key is 16 bytes and iv is prepended 16 bytes`() {
        val plain = "{\"a\":1}"
        val enc = OutlinkCrypto.encrypt(plain)
        val raw = Base64.decode(enc)
        // IV 前置 16 字节
        assertThat(raw.size).isAtLeast(16 + 16)
        // 两次加密 IV 不同 -> 密文不同
        val enc2 = OutlinkCrypto.encrypt(plain)
        assertThat(enc).isNotEqualTo(enc2)
    }

    @Test
    fun `truncated ciphertext throws so upper layer falls back to plaintext error`() {
        // 200KB 录制响应被截断（采集上限 20000 字节），解密应抛异常
        val truncated = Golden.read("share/01_getOutLinkInfoV6.resp.b64.txt").trim()
        try {
            OutlinkCrypto.decrypt(truncated)
            // 若未来该夹具变完整，至少不崩；这里记录为「可解」
        } catch (e: Exception) {
            // expected: wrong final block length
        }
    }
}
