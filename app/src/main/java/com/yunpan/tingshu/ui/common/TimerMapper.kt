package com.yunpan.tingshu.ui.common

import com.yunpan.tingshu.player.TimerConfig
import com.yunpan.tingshu.player.TimerUiState

/**
 * 睡眠定时 Sheet 选项 ↔ [TimerConfig] 映射，以及 [TimerUiState] → 倒计时 chip 文案（纯 JVM）。
 *
 * Sheet 选项：
 *  - 分钟档位 15/30/45/60/90
 *  - 本集结束后停止
 *  - 再播完 1/2/3/5/10 集 + 自定义 1..99
 */
object TimerMapper {

    /** 分钟快捷档位（分钟）。 */
    val minutePresets: List<Int> = listOf(15, 30, 45, 60, 90)

    /** 集数快捷档位（集）。 */
    val episodePresets: List<Int> = listOf(1, 2, 3, 5, 10)

    fun minutes(m: Int): TimerConfig = TimerConfig.Minutes(m)

    fun endOfEpisode(): TimerConfig = TimerConfig.EndOfEpisode

    /** n 夹到 1..99。 */
    fun episodes(n: Int): TimerConfig = TimerConfig.Episodes(n.coerceIn(1, 99))

    /** 倒计时 chip 文案。 */
    fun chipText(t: TimerUiState): String = when (t) {
        TimerUiState.Idle -> "定时未开启"
        is TimerUiState.MinutesLeft -> "剩余 ${Fmt.clockMMSS(t.remainMs)}"
        TimerUiState.EndOfEpisodeActive -> "本集结束后停止"
        is TimerUiState.EpisodesLeft -> "剩 ${t.remaining} 集"
        TimerUiState.Fired -> "已到点"
    }

    /** chip 是否可见（Idle/Fired 不显示）。 */
    fun chipVisible(t: TimerUiState): Boolean =
        t !is TimerUiState.Idle && t !is TimerUiState.Fired

    /** 是否支持续期：Minutes/Episodes 可续期，EndOfEpisode 无操作。 */
    fun canExtend(t: TimerUiState): Boolean =
        t is TimerUiState.MinutesLeft || t is TimerUiState.EpisodesLeft
}
