package com.yunpan.tingshu.player

import com.yunpan.tingshu.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 睡眠定时器配置（SPEC §4 + §10.4）。同一时刻仅一种生效，[SleepTimer.start] 会替换旧的。
 */
sealed class TimerConfig {
    /** N 分钟后停止。 */
    data class Minutes(val m: Int) : TimerConfig()

    /** 当前集自然播完后停止。 */
    data object EndOfEpisode : TimerConfig()

    /** 再播完 n 集后停止（n in 1..99）。 */
    data class Episodes(val n: Int) : TimerConfig()
}

/**
 * 睡眠定时器对 UI 的状态。
 */
sealed class TimerUiState {
    data object Idle : TimerUiState()
    /** 分钟模式：剩余毫秒。 */
    data class MinutesLeft(val remainMs: Long) : TimerUiState()
    data object EndOfEpisodeActive : TimerUiState()
    /** 集数模式：还剩几集要播完。 */
    data class EpisodesLeft(val remaining: Int) : TimerUiState()
    /** 到点/计数归零：只触发一次，服务收到后 pause + 释放前台服务。 */
    data object Fired : TimerUiState()
}

/**
 * 睡眠定时器（纯逻辑，注入 [Clock]）。
 *
 * 事件区分（关键）：
 *  - [manualAdvance]：用户手动按“下一首”。仅在 Episodes 模式消耗一集。
 *  - [automaticAdvance]：当前集自然播完、即将自动切到下一首。EndOfEpisode 在此 Fired；
 *    Episodes 在此 remaining-1（减到 0 时 Fired）。
 *  - 本集内 seek / 启动首集 不调用任何 advance，故不会误触发。
 *
 * [onTick] 由服务层 ~1s 调用（Minutes 模式据此用注入 clock 判断剩余）。
 */
class SleepTimer(private val clock: Clock) {

    private val _state = MutableStateFlow<TimerUiState>(TimerUiState.Idle)
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    private var cfg: TimerConfig? = null
    private var deadlineMs: Long = 0L          // Minutes 模式用
    private var firedLatch: Boolean = false   // 保证 Fired 仅一次

    /** 开始/替换定时。 */
    fun start(cfg: TimerConfig) {
        this.cfg = cfg
        firedLatch = false
        when (cfg) {
            is TimerConfig.Minutes -> {
                val m = cfg.m.coerceAtLeast(1)
                deadlineMs = clock.nowMs() + m * 60_000L
                _state.value = TimerUiState.MinutesLeft(deadlineMs - clock.nowMs())
            }
            TimerConfig.EndOfEpisode -> {
                _state.value = TimerUiState.EndOfEpisodeActive
            }
            is TimerConfig.Episodes -> {
                val n = cfg.n.coerceIn(1, 99)
                _state.value = TimerUiState.EpisodesLeft(n)
            }
        }
    }

    fun cancel() {
        cfg = null
        firedLatch = false
        _state.value = TimerUiState.Idle
    }

    /** 续期：Minutes +10 分钟；Episodes +1 集；EndOfEpisode 无操作。 */
    fun extend() {
        val c = cfg ?: return
        when (c) {
            is TimerConfig.Minutes -> {
                deadlineMs += 10 * 60_000L
                _state.value = TimerUiState.MinutesLeft(deadlineMs - clock.nowMs())
            }
            is TimerConfig.Episodes -> {
                val cur = (_state.value as? TimerUiState.EpisodesLeft)?.remaining ?: c.n
                _state.value = TimerUiState.EpisodesLeft(cur + 1)
            }
            TimerConfig.EndOfEpisode -> Unit
        }
    }

    /** 服务层 ~1s 调用。Minutes 模式判断是否到点。到点返回 Fired 且只一次。 */
    fun onTick(nowMs: Long = clock.nowMs()): TimerUiState {
        val c = cfg
        if (c is TimerConfig.Minutes && !firedLatch) {
            val remain = deadlineMs - nowMs
            if (remain <= 0) {
                fire()
            } else {
                _state.value = TimerUiState.MinutesLeft(remain)
            }
        }
        return _state.value
    }

    /** 用户手动“下一首”：仅 Episodes 模式消耗一集。 */
    fun manualAdvance() {
        val c = cfg ?: return
        if (firedLatch) return
        if (c is TimerConfig.Episodes) decrementEpisodes()
    }

    /** 当前集自然播完、即将自动切歌。EndOfEpisode 立即 Fired；Episodes 消耗一集。 */
    fun automaticAdvance() {
        val c = cfg ?: return
        if (firedLatch) return
        when (c) {
            TimerConfig.EndOfEpisode -> fire()
            is TimerConfig.Episodes -> decrementEpisodes()
            is TimerConfig.Minutes -> Unit
        }
    }

    private fun decrementEpisodes() {
        val cur = (_state.value as? TimerUiState.EpisodesLeft)?.remaining
            ?: (cfg as? TimerConfig.Episodes)?.n ?: return
        val next = cur - 1
        if (next <= 0) fire() else _state.value = TimerUiState.EpisodesLeft(next)
    }

    private fun fire() {
        firedLatch = true
        cfg = null
        _state.value = TimerUiState.Fired
    }
}
