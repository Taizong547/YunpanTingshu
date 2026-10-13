package com.yunpan.tingshu.player

/** 测试用响度 fake：记录 attach/setGain/release 调用。 */
class FakeLoudnessController : LoudnessController {
    var attachedSessionId: Int = 0
        private set
    var lastGainMB: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    override fun attach(audioSessionId: Int) { attachedSessionId = audioSessionId }
    override fun setGainMB(mB: Int) { lastGainMB = mB }
    override fun release() { releaseCount++; attachedSessionId = 0 }
}
