package com.yunpan.tingshu.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeGainTest {

    @Test fun stagesAre0_3_6_10() {
        assertThat(VolumeGain.STAGES_DB).containsExactly(0, 3, 6, 10).inOrder()
    }

    @Test fun toMilliBelsIsDbTimes100() {
        assertThat(VolumeGain.toMilliBels(0)).isEqualTo(0)
        assertThat(VolumeGain.toMilliBels(3)).isEqualTo(300)
        assertThat(VolumeGain.toMilliBels(10)).isEqualTo(1000)
    }

    @Test fun clampsToNearestStage() {
        assertThat(VolumeGain.clampDb(-5)).isEqualTo(0)
        assertThat(VolumeGain.clampDb(1)).isEqualTo(0)   // 距 0 更近
        assertThat(VolumeGain.clampDb(2)).isEqualTo(3)
        assertThat(VolumeGain.clampDb(5)).isEqualTo(6)
        assertThat(VolumeGain.clampDb(9)).isEqualTo(10)
        assertThat(VolumeGain.clampDb(99)).isEqualTo(10)
    }

    @Test fun indexOf() {
        assertThat(VolumeGain.indexOf(0)).isEqualTo(0)
        assertThat(VolumeGain.indexOf(3)).isEqualTo(1)
        assertThat(VolumeGain.indexOf(6)).isEqualTo(2)
        assertThat(VolumeGain.indexOf(10)).isEqualTo(3)
    }

    @Test fun nextCyclesThroughStages() {
        assertThat(VolumeGain.nextDb(0)).isEqualTo(3)
        assertThat(VolumeGain.nextDb(3)).isEqualTo(6)
        assertThat(VolumeGain.nextDb(6)).isEqualTo(10)
        assertThat(VolumeGain.nextDb(10)).isEqualTo(0)
    }
}
