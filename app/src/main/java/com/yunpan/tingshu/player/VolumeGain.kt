package com.yunpan.tingshu.player

/**
 * 响度增益（人声增强）纯逻辑：档位 → mB(clamp/序号)，便于 JVM 单测。
 * LoudnessEnhancer 用毫贝尔(mB = dB*100)。
 */
object VolumeGain {
    /** 可选档位（dB）：关闭 / +3 / +6 / +10。 */
    val STAGES_DB: List<Int> = listOf(0, 3, 6, 10)

    /** dB → mB。 */
    fun toMilliBels(db: Int): Int = db * 100

    /** 把任意 dB clamp 到最近的合法档位。 */
    fun clampDb(db: Int): Int {
        if (db <= 0) return STAGES_DB.first()
        return STAGES_DB.minByOrNull { kotlin.math.abs(it - db) }!!
    }

    /** 当前档位在 STAGES_DB 中的序号（找不到返回 0）。 */
    fun indexOf(db: Int): Int = STAGES_DB.indexOf(clampDb(db)).let { if (it < 0) 0 else it }

    /** 循环到下一档（OFF→+3→+6→+10→OFF）。 */
    fun nextDb(currentDb: Int): Int {
        val i = indexOf(currentDb)
        return STAGES_DB[(i + 1) % STAGES_DB.size]
    }
}
