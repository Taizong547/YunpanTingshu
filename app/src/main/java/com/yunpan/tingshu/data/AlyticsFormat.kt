package com.yunpan.tingshu.data

/** 听书分析时长格式化（纯函数，JVM 可测）：毫秒 → "x 小时 y 分"。 */
object AlyticsFormat {
    fun duration(ms: Long): String {
        if (ms <= 0) return "0 分"
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h} 小时 ${m} 分"
            h > 0 -> "${h} 小时"
            else -> "${m} 分"
        }
    }
}
