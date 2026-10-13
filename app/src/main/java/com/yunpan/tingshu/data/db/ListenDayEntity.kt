package com.yunpan.tingshu.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 听书时长按天聚合（SPEC §10.2）。dayStartMs = 当天 0 点 epoch ms（按设备本地日）。 */
@Entity(tableName = "listen_day")
data class ListenDayEntity(
    @PrimaryKey val dayStartMs: Long,
    val ms: Long
)
