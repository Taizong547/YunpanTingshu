package com.yunpan.tingshu.player

/** 测试用音频焦点 fake：手动模拟焦点变化。 */
class FakeAudioFocusController : AudioFocusController {
    private var cb: AudioFocusController.Callback? = null
    var granted: Boolean = true
    var abandonCount: Int = 0
        private set

    override fun request(): Boolean = granted
    override fun abandon() { abandonCount++ }
    override fun setCallback(cb: AudioFocusController.Callback) { this.cb = cb }

    fun simulateTransientLoss() { cb?.onTransientLoss() }
    fun simulateGain() { cb?.onGain() }
    fun simulatePermanentLoss() { cb?.onPermanentLoss() }
}
