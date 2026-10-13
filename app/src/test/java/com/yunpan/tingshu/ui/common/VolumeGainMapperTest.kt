package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.AlyticsFormat
import org.junit.Test

class VolumeGainMapperTest {
    @Test fun `labels`() {
        assertThat(VolumeGainMapper.label(0)).isEqualTo("关闭")
        assertThat(VolumeGainMapper.label(3)).isEqualTo("+3 dB")
        assertThat(VolumeGainMapper.label(6)).isEqualTo("+6 dB")
        assertThat(VolumeGainMapper.label(10)).isEqualTo("+10 dB")
    }
    @Test fun `next cycles`() {
        assertThat(VolumeGainMapper.next(0)).isEqualTo(3)
        assertThat(VolumeGainMapper.next(3)).isEqualTo(6)
        assertThat(VolumeGainMapper.next(6)).isEqualTo(10)
        assertThat(VolumeGainMapper.next(10)).isEqualTo(0)
    }
}

class AlyticsFormatTest {
    @Test fun `zero is 0 分`() {
        assertThat(AlyticsFormat.duration(0)).isEqualTo("0 分")
        assertThat(AlyticsFormat.duration(-5)).isEqualTo("0 分")
    }
    @Test fun `minutes only`() {
        assertThat(AlyticsFormat.duration(5 * 60_000L)).isEqualTo("5 分")
    }
    @Test fun `hours and minutes`() {
        assertThat(AlyticsFormat.duration(90 * 60_000L)).isEqualTo("1 小时 30 分")
    }
    @Test fun `hours only`() {
        assertThat(AlyticsFormat.duration(2 * 3_600_000L)).isEqualTo("2 小时")
    }
}
