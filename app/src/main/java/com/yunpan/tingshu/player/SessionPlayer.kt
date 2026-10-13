package com.yunpan.tingshu.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 包一层 [ForwardingPlayer] 给 MediaSession：把通知栏/锁屏的“上一首/下一首”
 * 路由到自管队列（引擎），而不是 ExoPlayer 自身的（空）时间线。
 * 播放/暂停/seek/倍速 仍由底层 ExoPlayer 原生处理。
 */
class SessionPlayer(
    delegate: ExoPlayer,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit
) : ForwardingPlayer(delegate) {

    override fun seekToNext() = onNext()
    override fun seekToNextMediaItem() = onNext()
    override fun seekToPrevious() = onPrev()
    override fun seekToPreviousMediaItem() = onPrev()

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun getAvailableCommands(): Player.Commands =
        Player.Commands.Builder()
            .addAll(super.getAvailableCommands())
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
}
