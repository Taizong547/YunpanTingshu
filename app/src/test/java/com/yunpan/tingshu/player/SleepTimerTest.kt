package com.yunpan.tingshu.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SleepTimerTest {

    private val clock = VirtualClock()
    private val timer = SleepTimer(clock)

    @Test fun minutesFiresWhenReached() {
        timer.start(TimerConfig.Minutes(30))
        assertThat(timer.state.value).isInstanceOf(TimerUiState.MinutesLeft::class.java)
        clock.advance(29 * 60_000L)
        timer.onTick()
        assertThat(timer.state.value).isInstanceOf(TimerUiState.MinutesLeft::class.java)
        clock.advance(61_000L) // 越过 30 分钟
        timer.onTick()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun minutesDoesNotFireBefore() {
        timer.start(TimerConfig.Minutes(15))
        clock.advance(14 * 60_000L)
        timer.onTick()
        assertThat(timer.state.value).isInstanceOf(TimerUiState.MinutesLeft::class.java)
    }

    @Test fun cancelPreventsFire() {
        timer.start(TimerConfig.Minutes(10))
        timer.cancel()
        clock.advance(99 * 60_000L)
        timer.onTick()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Idle)
    }

    @Test fun extendAddsTenMinutes() {
        timer.start(TimerConfig.Minutes(30))
        clock.advance(25 * 60_000L)
        timer.extend() // +10 -> 剩余 15 分
        timer.onTick()
        // 此刻距原定点还有 5 分，extend 后还有 15 分，不应 Fired
        clock.advance(10 * 60_000L)
        timer.onTick()
        assertThat(timer.state.value).isInstanceOf(TimerUiState.MinutesLeft::class.java)
        clock.advance(6 * 60_000L) // 越过
        timer.onTick()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun endOfEpisodeFiresOnNaturalAutoAdvance() {
        timer.start(TimerConfig.EndOfEpisode)
        timer.automaticAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun endOfEpisodeDoesNotFireOnManualNext() {
        timer.start(TimerConfig.EndOfEpisode)
        timer.manualAdvance() // 手动下一首不算“本集自然结束”
        assertThat(timer.state.value).isEqualTo(TimerUiState.EndOfEpisodeActive)
    }

    @Test fun episodesOneFiresAfterOneConsumed() {
        timer.start(TimerConfig.Episodes(1))
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(1))
        timer.automaticAdvance() // 消耗 1 集 -> 0 -> Fired
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun episodesTwoFiresAfterTwoConsumed() {
        timer.start(TimerConfig.Episodes(2))
        timer.automaticAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(1))
        timer.automaticAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun episodesThreeWithManualNextCounts() {
        timer.start(TimerConfig.Episodes(3))
        timer.manualAdvance() // 手动下一首计数
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(2))
        timer.automaticAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(1))
        timer.manualAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun extendEpisodesAddsOne() {
        timer.start(TimerConfig.Episodes(2))
        timer.manualAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(1))
        timer.extend()
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(2))
        timer.automaticAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(1))
    }

    @Test fun startReplacesPreviousConfig() {
        timer.start(TimerConfig.Minutes(30))
        timer.start(TimerConfig.Episodes(1)) // 替换
        clock.advance(99 * 60_000L)
        timer.onTick() // 分钟计时已被替换，不应 Fired
        assertThat(timer.state.value).isEqualTo(TimerUiState.EpisodesLeft(1))
        timer.automaticAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun firedOnlyOnce() {
        timer.start(TimerConfig.Minutes(1))
        clock.advance(10 * 60_000L)
        timer.onTick()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
        // 后续 tick / advance 不再改变
        clock.advance(10 * 60_000L)
        timer.onTick()
        timer.automaticAdvance()
        timer.manualAdvance()
        assertThat(timer.state.value).isEqualTo(TimerUiState.Fired)
    }

    @Test fun endOfEpisodeSeekDoesNotFire() {
        timer.start(TimerConfig.EndOfEpisode)
        // seek / 开播首集不调用 advance，状态保持
        assertThat(timer.state.value).isEqualTo(TimerUiState.EndOfEpisodeActive)
    }
}
