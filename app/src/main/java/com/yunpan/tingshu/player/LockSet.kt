package com.yunpan.tingshu.player

/**
 * 播放期间需持有的锁（PARTIAL_WAKE_LOCK + WiFiLock）。抽成接口以便单测断言 acquire/release。
 * acquire 幂等（已持有时重复调用不报错）；release 幂等。
 */
interface LockSet {
    fun acquire()
    fun release()
    fun isHeld(): Boolean
}

/** 测试用 fake：记录 acquire/release 次数与当前状态，无系统依赖。 */
class FakeLockSet : LockSet {
    var acquireCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set
    private var held: Boolean = false

    override fun acquire() {
        if (!held) { held = true; acquireCount++ }
    }

    override fun release() {
        if (held) { held = false; releaseCount++ }
    }

    override fun isHeld(): Boolean = held
}
