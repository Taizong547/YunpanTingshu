package com.yunpan.tingshu.player

import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * 真机响度增强：绑定 ExoPlayer audioSessionId。
 * onAudioSessionIdChanged 时重建；不支持的设备 try/catch 静默降级。
 */
class AndroidLoudnessController : LoudnessController {

    private var enhancer: LoudnessEnhancer? = null
    private var targetMB: Int = 0
    private var sessionId: Int = 0

    override fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == sessionId) {
            if (audioSessionId == sessionId) return
        }
        releaseInternal()
        sessionId = audioSessionId
        runCatching {
            LoudnessEnhancer(audioSessionId).also { e ->
                if (targetMB != 0) e.setTargetGain(targetMB)
                e.enabled = true
                enhancer = e
            }
        }.onFailure { Log.w("Loudness", "LoudnessEnhancer unavailable: ${it.message}") }
    }

    override fun setGainMB(mB: Int) {
        targetMB = mB
        runCatching { enhancer?.setTargetGain(mB) }
    }

    override fun release() = releaseInternal()

    private fun releaseInternal() {
        runCatching {
            enhancer?.enabled = false
            enhancer?.release()
        }
        enhancer = null
        sessionId = 0
    }
}
