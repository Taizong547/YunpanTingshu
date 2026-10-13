package com.yunpan.tingshu.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** 时钟抽象：真机用系统时间，测试注入虚拟时钟。 */
interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * 东八区（UTC+8 固定）时间工具。
 * 139 签名要求 ts = `yyyy-MM-dd HH:mm:ss`，且**固定 UTC+8**（不随设备时区变化）。
 */
object TimeFmt {
    private val EAST8: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    /** 把 epoch ms 格式化为东八区 `yyyy-MM-dd HH:mm:ss`。 */
    fun east8(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = EAST8
        return fmt.format(Date(ms))
    }

    fun nowEast8(clock: Clock = SystemClock): String = east8(clock.nowMs())
}

/** 16 位随机串 [A-Za-z0-9]（与 TS randomString 一致）。 */
object Rand {
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun next16(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(CHARS[Random.nextInt(CHARS.length)]) }
        return sb.toString()
    }
}
