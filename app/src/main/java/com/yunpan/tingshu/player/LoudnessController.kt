package com.yunpan.tingshu.player

/**
 * 响度增益控制（测试可注入 Fake）。绑定 ExoPlayer 输出 audioSessionId。
 */
interface LoudnessController {
    /** 拿到新的 audioSessionId 后绑定/重建底层效果器。 */
    fun attach(audioSessionId: Int)
    /** 设置目标增益（毫贝尔）。 */
    fun setGainMB(mB: Int)
    /** 释放底层效果器。 */
    fun release()
}
