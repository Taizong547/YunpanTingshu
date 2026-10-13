package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.player.TimerConfig
import com.yunpan.tingshu.player.TimerUiState
import org.junit.Test

class TimerMapperTest {

    @Test
    fun `minute and episode presets`() {
        assertThat(TimerMapper.minutePresets).containsExactly(15, 30, 45, 60, 90).inOrder()
        assertThat(TimerMapper.episodePresets).containsExactly(1, 2, 3, 5, 10).inOrder()
    }

    @Test
    fun `minutes maps to Minutes config`() {
        assertThat(TimerMapper.minutes(30)).isEqualTo(TimerConfig.Minutes(30))
    }

    @Test
    fun `episodes clamps to 1 99`() {
        assertThat(TimerMapper.episodes(1)).isEqualTo(TimerConfig.Episodes(1))
        assertThat(TimerMapper.episodes(99)).isEqualTo(TimerConfig.Episodes(99))
        assertThat(TimerMapper.episodes(0)).isEqualTo(TimerConfig.Episodes(1))
        assertThat(TimerMapper.episodes(500)).isEqualTo(TimerConfig.Episodes(99))
    }

    @Test
    fun `chip text for all timer states`() {
        assertThat(TimerMapper.chipText(TimerUiState.Idle)).isEqualTo("定时未开启")
        assertThat(TimerMapper.chipText(TimerUiState.MinutesLeft(29 * 60_000L + 59_000L)))
            .isEqualTo("剩余 29:59")
        assertThat(TimerMapper.chipText(TimerUiState.EndOfEpisodeActive)).isEqualTo("本集结束后停止")
        assertThat(TimerMapper.chipText(TimerUiState.EpisodesLeft(3))).isEqualTo("剩 3 集")
        assertThat(TimerMapper.chipText(TimerUiState.Fired)).isEqualTo("已到点")
    }

    @Test
    fun `chip visible excludes idle and fired`() {
        assertThat(TimerMapper.chipVisible(TimerUiState.Idle)).isFalse()
        assertThat(TimerMapper.chipVisible(TimerUiState.Fired)).isFalse()
        assertThat(TimerMapper.chipVisible(TimerUiState.MinutesLeft(1_000))).isTrue()
        assertThat(TimerMapper.chipVisible(TimerUiState.EndOfEpisodeActive)).isTrue()
        assertThat(TimerMapper.chipVisible(TimerUiState.EpisodesLeft(1))).isTrue()
    }

    @Test
    fun `can extend only minutes or episodes`() {
        assertThat(TimerMapper.canExtend(TimerUiState.MinutesLeft(1_000))).isTrue()
        assertThat(TimerMapper.canExtend(TimerUiState.EpisodesLeft(1))).isTrue()
        assertThat(TimerMapper.canExtend(TimerUiState.EndOfEpisodeActive)).isFalse()
        assertThat(TimerMapper.canExtend(TimerUiState.Idle)).isFalse()
    }
}
