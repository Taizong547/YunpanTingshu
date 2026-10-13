package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BreadcrumbUtilsTest {

    @Test
    fun `dedupe collapses adjacent same-name`() {
        assertThat(BreadcrumbUtils.dedupe(listOf("视频下载内容", "视频下载内容", "老梁说科举", "老梁说科举")))
            .containsExactly("视频下载内容", "老梁说科举").inOrder()
    }

    @Test
    fun `dedupe keeps non-adjacent repeats`() {
        assertThat(BreadcrumbUtils.dedupe(listOf("A", "B", "A")))
            .containsExactly("A", "B", "A").inOrder()
    }

    @Test
    fun `dedupe trims and drops blanks`() {
        assertThat(BreadcrumbUtils.dedupe(listOf(" 根 ", "", "根", "  子 ")))
            .containsExactly("根", "子").inOrder()
    }

    @Test
    fun `dedupe empty input`() {
        assertThat(BreadcrumbUtils.dedupe(emptyList())).isEmpty()
    }

    @Test
    fun `ellipsize keeps short breadcrumb`() {
        assertThat(BreadcrumbUtils.ellipsizeMiddle(listOf("A", "B", "C"), maxSegments = 4))
            .containsExactly("A", "B", "C").inOrder()
    }

    @Test
    fun `ellipsize collapses long breadcrumb to head dot dot tail`() {
        val out = BreadcrumbUtils.ellipsizeMiddle(listOf("A", "B", "C", "D", "E"), maxSegments = 4)
        assertThat(out).containsExactly("A", "…", "E").inOrder()
    }

    @Test
    fun `normalize chains dedupe then ellipsize`() {
        val out = BreadcrumbUtils.normalize(
            listOf("X", "X", "Y", "Z", "W", "V"), maxSegments = 4
        )
        assertThat(out).containsExactly("X", "…", "V").inOrder()
    }
}
