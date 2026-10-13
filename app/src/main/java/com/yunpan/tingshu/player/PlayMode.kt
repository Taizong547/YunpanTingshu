package com.yunpan.tingshu.player

/**
 * 播放模式（SPEC §4）。
 * - [SEQUENTIAL] 顺序播放：播到末集 next 返回 null（停止）。
 * - [REPEAT_ONE] 单曲循环：next 不前进，重播当前集。
 * - [SHUFFLE] 随机：在未播集合中随机取一首，播完一轮后重新洗牌（不越界、不连续重复）。
 */
enum class PlayMode {
    SEQUENTIAL,
    REPEAT_ONE,
    SHUFFLE
}
