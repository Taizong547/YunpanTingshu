package com.yunpan.tingshu.player

/**
 * 智能跳过片头/片尾的纯逻辑（无 Android 依赖，便于单测）。
 *
 * - 片头跳过：仅当"从头开始播"（resumeMs <= 0，即没有断点续播位置）且配置 > 0 时，seek 到 introMs；
 *   若用户有断点位置（resumeMs > 0），不跳，直接续播。
 * - 片尾跳过：当剩余时长 <= outroMs 且非单曲循环时，自动切下一集。
 */
object SkipTiming {

    /** 开播时实际应 seek 到的位置。 */
    fun introSeekMs(resumeMs: Long, introMs: Long): Long {
        val base = resumeMs.coerceAtLeast(0L)
        return if (introMs > 0L && base <= 0L) introMs.coerceAtLeast(0L) else base
    }

    /** 是否应自动切下一集（片尾跳过触发）。 */
    fun shouldAutoNext(positionMs: Long, durationMs: Long, outroMs: Long): Boolean {
        if (outroMs <= 0L) return false
        if (durationMs <= 0L) return false
        if (positionMs < 0L) return false
        return (durationMs - positionMs) <= outroMs
    }

    /** 预设档位（秒）。 */
    val PRESETS_SEC = listOf(0, 15, 30, 60, 90)
}
