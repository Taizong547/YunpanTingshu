package com.yunpan.tingshu.player

import com.yunpan.tingshu.util.Clock

/** 测试用可推进虚拟时钟。 */
class VirtualClock : Clock {
    var ms: Long = 0L
    override fun nowMs(): Long = ms
    fun advance(deltaMs: Long) { ms += deltaMs }
}
