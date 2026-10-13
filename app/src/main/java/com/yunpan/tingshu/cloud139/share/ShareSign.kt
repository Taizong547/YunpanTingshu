package com.yunpan.tingshu.cloud139.share

import com.yunpan.tingshu.cloud139.Cloud139Signer
import com.yunpan.tingshu.util.Rand
import com.yunpan.tingshu.util.TimeFmt

/**
 * 分享 mcloud-sign（share-api.md §5）。公式与登录态同构，只是对「加密前明文 JSON」计算：
 *   sign = MD5( MD5(Base64(字符升序(encodeURIComponent(明文JSON)))) + MD5(timeStr+":"+rand) ).toUpperCase()
 * 输出头值 `timeStr,rand,sign`。
 */
object ShareSign {

    fun sign(plainJson: String, timeStr: String, rand: String): String =
        Cloud139Signer.sign(plainJson, timeStr, rand)

    /** 生成一次请求用的签名头值，并返回 timeStr/rand 供上层/测试使用。 */
    fun build(plainJson: String, timeStr: String = TimeFmt.nowEast8(), rand: String = Rand.next16()): Triple<String, String, String> {
        val s = sign(plainJson, timeStr, rand)
        return Triple(timeStr, rand, "$timeStr,$rand,$s")
    }
}
