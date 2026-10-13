package com.yunpan.tingshu.ui.common

/** 音量增益档位（人声增强）纯映射：dB ↔ 中文标签。 */
object VolumeGainMapper {
    /** 可选档位（与播放引擎 availableGainStages 对齐）。 */
    val stages: List<Int> = listOf(0, 3, 6, 10)

    fun label(db: Int): String = when (db) {
        0 -> "关闭"
        3 -> "+3 dB"
        6 -> "+6 dB"
        10 -> "+10 dB"
        else -> "+${db} dB"
    }

    /** 循环到下一档（OFF→+3→+6→+10→OFF）。 */
    fun next(current: Int): Int {
        val idx = stages.indexOf(current)
        return if (idx < 0) stages.first() else stages[(idx + 1) % stages.size]
    }
}
