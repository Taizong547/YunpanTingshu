package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FmtTest {

    @Test
    fun `clock shows mm ss under an hour`() {
        assertThat(Fmt.clock(0)).isEqualTo("00:00")
        assertThat(Fmt.clock(5_000)).isEqualTo("00:05")
        assertThat(Fmt.clock(65_000)).isEqualTo("01:05")
        assertThat(Fmt.clock(3_599_000)).isEqualTo("59:59")
    }

    @Test
    fun `clock shows h mm ss over an hour`() {
        assertThat(Fmt.clock(3_600_000)).isEqualTo("1:00:00")
        assertThat(Fmt.clock(3_661_000)).isEqualTo("1:01:01")
        assertThat(Fmt.clock(36_610_000)).isEqualTo("10:10:10")
    }

    @Test
    fun `clockMMSS always mm ss even over an hour`() {
        assertThat(Fmt.clockMMSS(90 * 60_000L)).isEqualTo("90:00")
        assertThat(Fmt.clockMMSS(29 * 60_000L + 59_000L)).isEqualTo("29:59")
        assertThat(Fmt.clockMMSS(-5)).isEqualTo("00:00")
    }

    @Test
    fun `stats formats Chinese listening time`() {
        assertThat(Fmt.stats(0)).isEqualTo("0 分钟")
        assertThat(Fmt.stats(5 * 60_000L)).isEqualTo("5 分钟")
        assertThat(Fmt.stats(65 * 60_000L)).isEqualTo("1 小时 5 分")
        assertThat(Fmt.stats(120 * 60_000L)).isEqualTo("2 小时")
        assertThat(Fmt.stats(90_000)).isEqualTo("2 分钟") // rounds up
    }

    @Test
    fun `relativeDay labels today yesterday and date`() {
        // 2026-10-04 12:00 Asia/Shanghai
        val now = 1_780_593_600_000L // 2026-10-04 00:00 CST approx; use computed dayStart
        val todayStart = Fmt.dayStart(now)
        assertThat(Fmt.relativeDay(todayStart + 3_600_000L, todayStart + 8 * 3_600_000L)).isEqualTo("今天")
        assertThat(Fmt.relativeDay(todayStart - 86_400_000L, todayStart + 8 * 3_600_000L)).isEqualTo("昨天")
        val threeDaysAgo = todayStart - 3 * 86_400_000L
        assertThat(Fmt.relativeDay(threeDaysAgo, todayStart + 8 * 3_600_000L)).matches("\\d{4}-\\d{2}-\\d{2}")
    }

    @Test
    fun `formatSize human readable`() {
        assertThat(Fmt.formatSize(0)).isEmpty()
        assertThat(Fmt.formatSize(2048)).isEqualTo("2.0 KB")
        assertThat(Fmt.formatSize(5 * 1024 * 1024L)).isEqualTo("5.0 MB")
    }
}
