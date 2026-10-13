package com.yunpan.tingshu.util

import java.security.MessageDigest

/** MD5，输出十六进制小写（与 TS crypto-js MD5().toString() 默认小写一致）。 */
object Md5 {
    private val hexChars = "0123456789abcdef".toCharArray()

    fun hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(out)
    }
}
