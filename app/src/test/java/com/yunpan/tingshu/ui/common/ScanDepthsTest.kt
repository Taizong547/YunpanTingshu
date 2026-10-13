package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.model.ScanDepth
import org.junit.Test

class ScanDepthsTest {

    @Test
    fun `ordered contains all depths in sheet order`() {
        assertThat(ScanDepths.ordered).containsExactly(
            ScanDepth.CURRENT, ScanDepth.LEVEL1, ScanDepth.LEVEL2,
            ScanDepth.LEVEL3, ScanDepth.LEVEL5, ScanDepth.ALL
        ).inOrder()
    }

    @Test
    fun `byIndex and indexOf round trip`() {
        ScanDepths.ordered.forEachIndexed { i, d ->
            assertThat(ScanDepths.byIndex(i)).isEqualTo(d)
            assertThat(ScanDepths.indexOf(d)).isEqualTo(i)
        }
    }

    @Test
    fun `byIndex out of range falls back to current`() {
        assertThat(ScanDepths.byIndex(-1)).isEqualTo(ScanDepth.CURRENT)
        assertThat(ScanDepths.byIndex(99)).isEqualTo(ScanDepth.CURRENT)
    }

    @Test
    fun `labels are Chinese and distinct`() {
        assertThat(ScanDepths.label(ScanDepth.CURRENT)).isEqualTo("仅当前层")
        assertThat(ScanDepths.label(ScanDepth.ALL)).isEqualTo("全部子级")
        assertThat(ScanDepths.ordered.map { ScanDepths.label(it) }.toSet()).hasSize(6)
    }
}
