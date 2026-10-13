package com.yunpan.tingshu.cloud139

import com.yunpan.tingshu.util.Base64
import com.yunpan.tingshu.util.JsEncoder
import com.yunpan.tingshu.util.Md5

/**
 * 139 签名（对应 TS cloud139Sign，逐字节照 SPEC §3.2 / auth.ts:31-38）。
 *
 * 1. encoded = encodeURIComponent(jsonBody)（空格=>%20，白名单外按 UTF-8 字节 %XX 大写）
 * 2. sorted  = 把 encoded 每个字符（UTF-16 code unit）按 Unicode 升序排序后拼接
 * 3. bodyBase64 = 对 sorted 的 UTF-8 字节做标准 base64
 * 4. hash1 = md5(bodyBase64) 小写；hash2 = md5(ts + ":" + rand) 小写
 * 5. sign = md5(hash1 + hash2) 小写后再 toUpperCase()
 *
 * 纯函数、无 Android 依赖。
 */
object Cloud139Signer {

    fun sign(body: String, ts: String, rand: String): String {
        val encoded = JsEncoder.encode(body)
        // JS encoded.split('').sort().join('')：encoded 全是 ASCII，按 UTF-16 char（即 char code）升序
        val sorted = encoded.toCharArray().sorted().joinToString("")
        val bodyBase64 = Base64.encodeUtf8(sorted)
        val hash1 = Md5.hex(bodyBase64)
        val hash2 = Md5.hex("$ts:$rand")
        return Md5.hex(hash1 + hash2).uppercase()
    }
}
