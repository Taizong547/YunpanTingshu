package com.yunpan.tingshu.ui.common

/** 定时滚轮（时+分）→ 总分钟，边界校验（纯逻辑，JVM 可测）。 */
object SleepWheel {
    const val MAX_MINUTES = 240

    /** @param hours 0..3 @param minutes 0..59 */
    fun toMinutes(hours: Int, minutes: Int): Int {
        val h = hours.coerceIn(0, 3)
        val m = minutes.coerceIn(0, 59)
        return (h * 60 + m).coerceIn(1, MAX_MINUTES)
    }

    /** 集数滚轮边界 1..50。 */
    fun episodes(raw: Int): Int = raw.coerceIn(1, 50)
}
