package com.yunpan.tingshu

import java.io.InputStream

/** 读取 test/resources/golden 下的夹具。 */
object Golden {
    fun read(path: String): String {
        val stream: InputStream = this::class.java.getResourceAsStream("/golden/$path")
            ?: throw IllegalStateException("找不到测试资源: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
