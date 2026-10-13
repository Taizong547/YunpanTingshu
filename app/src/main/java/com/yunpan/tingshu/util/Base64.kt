package com.yunpan.tingshu.util

/**
 * 标准 Base64（RFC 4648，等价 JS btoa / Java 默认 Base64）。
 *
 * 刻意纯 Kotlin 实现，不依赖 android.util.Base64（它在本地 JVM 单测里是抛 "not mocked" 的桩），
 * 也不依赖 java.util.Base64（API 26+，本工程 minSdk 24）。这样在真机与纯 JVM 黄金向量测试里行为逐字节一致。
 * 仅处理 UTF-8 安全的字节序列；文本先转 UTF-8 字节再编码。
 */
object Base64 {
    private val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
    private const val PAD = '='

    /** 标准表，反向索引：字符 -> 6bit 值，非法为 -1。 */
    private val DECODE = IntArray(128) { -1 }.also { arr ->
        for (i in ALPHABET.indices) arr[ALPHABET[i].code] = i
    }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(if (i + 1 < data.size) ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else PAD)
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 0x3F] else PAD)
            i += 3
        }
        return out.toString()
    }

    /** 解码标准 base64；对非法长度/字符抛 IllegalArgumentException（与 JS atob 失败语义一致）。 */
    fun decode(s: String): ByteArray {
        // 收集有效 base64 字符（忽略空白），并记录 padding 数。
        val data = StringBuilder(s.length)
        for (c in s) {
            if (c == PAD) continue
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
            if (c.code >= 128 || DECODE[c.code] < 0) {
                throw IllegalArgumentException("The string to be decoded is not correctly encoded.")
            }
            data.append(c)
        }
        val len = data.length
        if (len % 4 == 1) throw IllegalArgumentException("The string to be decoded is not correctly encoded.")
        // 去掉 padding 后，剩余长度模 4 为 0/2/3，对应输出字节数
        val outLen = (len / 4) * 3 + when (len % 4) { 2 -> 1; 3 -> 2; else -> 0 }
        val out = ByteArray(outLen)
        var o = 0
        var i = 0
        while (i < len) {
            val v0 = DECODE[data[i].code]
            val v1 = DECODE[data[i + 1].code]
            out[o++] = ((v0 shl 2) or (v1 ushr 4)).toByte()
            if (i + 2 < len) {
                val v2 = DECODE[data[i + 2].code]
                out[o++] = (((v1 and 0x0F) shl 4) or (v2 ushr 2)).toByte()
                if (i + 3 < len) {
                    val v3 = DECODE[data[i + 3].code]
                    out[o++] = (((v2 and 0x03) shl 6) or v3).toByte()
                }
            }
            i += 4
        }
        return out
    }

    fun encodeUtf8(s: String): String = encode(s.toByteArray(Charsets.UTF_8))

    /** 解码并按 UTF-8 转字符串（等价 TS decodeURIComponent(atob(...)) 的 UTF-8 语义）。 */
    fun decodeToStringUtf8(s: String): String = String(decode(s), Charsets.UTF_8)
}
