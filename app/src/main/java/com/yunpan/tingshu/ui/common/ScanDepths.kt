package com.yunpan.tingshu.ui.common

import com.yunpan.tingshu.data.model.ScanDepth

/**
 * 递归深度选项 ↔ [ScanDepth] 映射（纯 JVM，可单测）。
 * UI BottomSheet 按 [ordered] 顺序展示，[label] 为统一中文文案。
 */
object ScanDepths {

    /** 与 BottomSheet RadioButton 顺序一致。 */
    val ordered: List<ScanDepth> = listOf(
        ScanDepth.CURRENT,
        ScanDepth.LEVEL1,
        ScanDepth.LEVEL2,
        ScanDepth.LEVEL3,
        ScanDepth.LEVEL5,
        ScanDepth.ALL
    )

    fun label(d: ScanDepth): String = when (d) {
        ScanDepth.CURRENT -> "仅当前层"
        ScanDepth.LEVEL1 -> "向下 1 级"
        ScanDepth.LEVEL2 -> "向下 2 级"
        ScanDepth.LEVEL3 -> "向下 3 级"
        ScanDepth.LEVEL5 -> "向下 5 级"
        ScanDepth.ALL -> "全部子级"
    }

    /** 取第 i 个选项（与 RadioGroup 子 view 顺序对应），越界回退 CURRENT。 */
    fun byIndex(index: Int): ScanDepth =
        ordered.getOrElse(index) { ScanDepth.CURRENT }

    fun indexOf(d: ScanDepth): Int = ordered.indexOf(d).coerceAtLeast(0)
}
