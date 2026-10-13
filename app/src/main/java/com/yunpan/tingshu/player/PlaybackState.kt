package com.yunpan.tingshu.player

import com.yunpan.tingshu.data.model.CloudFile

/**
 * 对 UI 暴露的播放态（[PlaybackManager.state]）。
 * UI 只观察本数据类，不直接接触 ExoPlayer。
 */
data class PlaybackState(
    val current: CloudFile? = null,
    val position: Int = -1,          // 队列索引（0 起）；无队列 = -1
    val size: Int = 0,                // 队列总数
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val playMode: PlayMode = PlayMode.SEQUENTIAL,
    val timer: TimerUiState = TimerUiState.Idle,
    val albumTitle: String = "",
    val error: String? = null,
    val volumeGainDb: Int = 0,
    val availableGainStages: List<Int> = VolumeGain.STAGES_DB
) {
    val hasQueue: Boolean get() = size > 0
}
