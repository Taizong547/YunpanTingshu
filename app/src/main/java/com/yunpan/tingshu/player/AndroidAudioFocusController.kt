package com.yunpan.tingshu.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 真机音频焦点处理（Media3 1.4 未暴露 setHandleAudioFocus，手动实现）。
 * API 26+ 用 AudioFocusRequest；API 24/25 回退到旧版 requestAudioFocus(listener)。
 * - 永久 LOSS → 暂停；短暂 LOSS → 暂停并记录；GAIN 且原在播则续播。
 */
class AndroidAudioFocusController(context: Context) : AudioFocusController {

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var cb: AudioFocusController.Callback? = null
    private var granted: Boolean = false

    private val listener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> cb?.onPermanentLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> cb?.onTransientLoss()
            AudioManager.AUDIOFOCUS_GAIN -> cb?.onGain()
        }
    }

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var request: AudioFocusRequest? = null

    override fun request(): Boolean {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener)
                .build()
            request = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        granted = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return granted
    }

    override fun abandon() {
        if (!granted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(listener)
        }
        granted = false
    }

    override fun setCallback(cb: AudioFocusController.Callback) { this.cb = cb }
}
