package com.yunpan.tingshu.ui.common

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 中文展示格式化（纯 JVM，可单测）。
 * - [clock]：进度/总时长 h:mm:ss 或 mm:ss
 * - [clockMMSS]：倒计时统一 mm:ss（可超过 60 分）
 * - [stats]：听书时长统计「X 小时 Y 分 / X 分钟」
 * - [relativeDay]：历史相对时间「今天 / 昨天 / yyyy-MM-dd」
 * - [formatSize]：字节大小中文
 *
 * 日期一律按 Asia/Shanghai 当日 0 点切日（与 listenDao.todayMs 口径一致）。
 */
object Fmt {

    private val SHANGHAI: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** epoch ms 所在的上海时区当日 0 点 epoch ms。 */
    fun dayStart(epochMs: Long): Long {
        val cal = Calendar.getInstance(SHANGHAI)
        cal.timeInMillis = epochMs
        cal[Calendar.HOUR_OF_DAY] = 0
        cal[Calendar.MINUTE] = 0
        cal[Calendar.SECOND] = 0
        cal[Calendar.MILLISECOND] = 0
        return cal.timeInMillis
    }

    /** 进度/总时长：>=1 小时显示 h:mm:ss，否则 mm:ss。 */
    fun clock(ms: Long): String {
        val v = if (ms < 0) 0L else ms
        val totalSec = v / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0)
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else
            String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 睡眠倒计时：统一 mm:ss（90 分钟即 90:00）。 */
    fun clockMMSS(ms: Long): String {
        val v = if (ms < 0) 0L else ms
        val totalSec = v / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 听书时长统计中文。 */
    fun stats(ms: Long): String {
        if (ms <= 0) return "0 分钟"
        val totalMin = Math.round(ms / 60000.0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h} 小时 ${m} 分"
            h > 0 -> "${h} 小时"
            else -> "${m} 分钟"
        }
    }

    /**
     * 历史相对时间标签。
     * @param epochMs 历史记录时间
     * @param nowMs   当前时间
     */
    fun relativeDay(epochMs: Long, nowMs: Long): String {
        val today = dayStart(nowMs)
        val item = dayStart(epochMs)
        val diffDays = (today - item) / 86_400_000L
        return when (diffDays) {
            0L -> "今天"
            1L -> "昨天"
            else -> {
                val cal = Calendar.getInstance(SHANGHAI)
                cal.timeInMillis = epochMs
                String.format(
                    Locale.US, "%04d-%02d-%02d",
                    cal[Calendar.YEAR], cal[Calendar.MONTH] + 1, cal[Calendar.DAY_OF_MONTH]
                )
            }
        }
    }

    /** 字节大小中文。 */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        if (kb < 1) return "${bytes} B"
        val mb = kb / 1024.0
        if (mb < 1) return String.format(Locale.US, "%.1f KB", kb)
        val gb = mb / 1024.0
        if (gb < 1) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", gb)
    }

    /** 音频副信息：有时长显时长，否则显大小（空则空串）。 */
    fun audioSub(durationSec: Long, size: Long): String {
        if (durationSec > 0) return clock(durationSec * 1000)
        return formatSize(size)
    }
}
