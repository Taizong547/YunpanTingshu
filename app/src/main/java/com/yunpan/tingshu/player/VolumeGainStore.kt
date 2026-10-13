package com.yunpan.tingshu.player

/** 响度档位持久化（测试可注入 fake）。 */
interface VolumeGainStore {
    suspend fun load(): Int
    suspend fun save(db: Int)
}
