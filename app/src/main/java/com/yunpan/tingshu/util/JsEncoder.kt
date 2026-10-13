package com.yunpan.tingshu.util

/**
 * 复刻 JS `encodeURIComponent`（逐字节）：
 *  - 不编码集合（白名单）：A-Z a-z 0-9 - _ . ! ~ * ' ( )
 *  - 其余字符按 UTF-8 字节逐字节输出 `%XX`（大写十六进制）。
 *  - 空格 => `%20`（注意：不是 URLEncoder 的 `+`）。
 *
 * 为什么自己实现：Kotlin URLEncoder 会把空格编成 `+`、且对 `!~*'()` 等也编码，与 JS 不一致；
 * 用白名单最稳妥。编码按 **code point** 迭代（emoji 等 astral 字符走 4 字节 UTF-8，与 JS 一致），
 * 而不是按 UTF-16 char（避免把代理对错编成 CESU-8）。
 */
object JsEncoder {
    private val UNRESERVED = BooleanArray(128).also { arr ->
        for (c in '0'..'9') arr[c.code] = true
        for (c in 'A'..'Z') arr[c.code] = true
        for (c in 'a'..'z') arr[c.code] = true
        // - _ . ! ~ * ' ( )
        for (c in listOf('-', '_', '.', '!', '~', '*', '\'', '(', ')')) arr[c.code] = true
    }
    private val hex = "0123456789ABCDEF".toCharArray()

    fun encode(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp < 128 && UNRESERVED[cp]) {
                out.append(cp.toChar())
            } else {
                // 取该 code point 的 UTF-8 字节
                val buf = ByteArray(4)
                val n = encodeUtf8CodePoint(cp, buf)
                for (k in 0 until n) {
                    val b = buf[k].toInt() and 0xFF
                    out.append('%')
                    out.append(hex[b ushr 4])
                    out.append(hex[b and 0x0F])
                }
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun encodeUtf8CodePoint(cp: Int, out: ByteArray): Int {
        return when {
            cp < 0x80 -> { out[0] = cp.toByte(); 1 }
            cp < 0x800 -> {
                out[0] = (0xC0 or (cp shr 6)).toByte()
                out[1] = (0x80 or (cp and 0x3F)).toByte()
                2
            }
            cp < 0x10000 -> {
                out[0] = (0xE0 or (cp shr 12)).toByte()
                out[1] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[2] = (0x80 or (cp and 0x3F)).toByte()
                3
            }
            else -> {
                out[0] = (0xF0 or (cp shr 18)).toByte()
                out[1] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                out[2] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[3] = (0x80 or (cp and 0x3F)).toByte()
                4
            }
        }
    }
}
