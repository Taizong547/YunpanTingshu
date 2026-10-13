package com.yunpan.tingshu.player

import com.yunpan.tingshu.data.model.CloudFile
import kotlin.random.Random

/**
 * 自管播放队列（纯 Kotlin，可 JVM 单测）。
 *
 * 只负责“当前索引 + 三模式下 next/prev 的目标索引”，不做网络/解析/播放器控制。
 * 自然顺序由扫描层保证，队列只负责索引。
 *
 * SHUFFLE 语义：维护 [history]（已播放原始索引栈，栈顶=当前）与 [upNext]（打乱的待播索引）。
 *  - next()：从 upNext 取一首；upNext 空则把“除当前外的所有索引”重新洗牌补齐，保证不连续重复、不越界、播完一轮后收敛到新一轮。
 *  - prev()：回退到 history 上一首；把当前索引塞回 upNext 队首。
 *  REPEAT_ONE：next/prev 都停在当前（由播放器重播本集）。
 *  SEQUENTIAL：next 到末集返回 null（停止）；prev 到首集返回 null。
 */
class PlayerQueue(
    private val random: Random = Random.Default
) {
    private var items: List<CloudFile> = emptyList()

    /** 当前原始索引；空队列 = -1。 */
    var index: Int = -1
        private set

    var mode: PlayMode = PlayMode.SEQUENTIAL
        private set

    // ---- SHUFFLE 状态 ----
    private val history = ArrayDeque<Int>()   // 栈顶 = 当前
    private val upNext = ArrayDeque<Int>()   // 打乱的待播索引

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun current(): CloudFile? = items.getOrNull(index)

    /** 第 i 项（不移动指针）。 */
    fun peek(i: Int): CloudFile? = items.getOrNull(i)

    /**
     * 重新装载列表并定位到 [startIndex]。切到 SHUFFLE 时会据此生成随机序列。
     */
    fun setList(list: List<CloudFile>, startIndex: Int) {
        items = list.toList()
        index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.size - 1)
        rebuildShuffle()
    }

    fun setMode(mode: PlayMode) {
        if (this.mode == mode) return
        this.mode = mode
        if (mode == PlayMode.SHUFFLE) rebuildShuffle()
    }

    /** 随机重洗（保留当前为当前）。 */
    fun reshuffle() = rebuildShuffle()

    private fun rebuildShuffle() {
        history.clear()
        upNext.clear()
        if (index in items.indices) {
            history.addLast(index)
            upNext.addAll(items.indices.filter { it != index }.shuffled(random))
        }
    }

    /** 跳到指定索引（用户点列表某一项）。返回是否成功。 */
    fun moveTo(i: Int): Boolean {
        if (i !in items.indices) return false
        index = i
        // SHUFFLE：把跳转项纳入历史，重置 upNext 为其余未播
        if (mode == PlayMode.SHUFFLE) rebuildShuffle()
        return true
    }

    /**
     * 下一首。返回目标索引；返回 null 表示“应当停止”（SEQUENTIAL 到末集）。
     * REPEAT_ONE 返回当前索引（重播本集）。
     */
    fun next(): Int? {
        if (items.isEmpty()) return null
        return when (mode) {
            PlayMode.SEQUENTIAL -> {
                val t = index + 1
                if (t < items.size) {
                    index = t
                    t
                } else null
            }
            PlayMode.REPEAT_ONE -> index
            PlayMode.SHUFFLE -> {
                if (upNext.isEmpty()) {
                    // 播完一轮：除当前外重新洗牌
                    upNext.addAll(items.indices.filter { it != index }.shuffled(random))
                }
                val t = upNext.removeFirstOrNull() ?: return index
                history.addLast(t)
                index = t
                t
            }
        }
    }

    /**
     * 上一首。返回目标索引；返回 null 表示已在首集（SEQUENTIAL）无可回退。
     * REPEAT_ONE 返回当前索引。SHUFFLE 回退到历史上一首。
     */
    fun prev(): Int? {
        if (items.isEmpty()) return null
        return when (mode) {
            PlayMode.SEQUENTIAL -> {
                val t = index - 1
                if (t >= 0) {
                    index = t
                    t
                } else null
            }
            PlayMode.REPEAT_ONE -> index
            PlayMode.SHUFFLE -> {
                if (history.size >= 2) {
                    val cur = history.removeLast()
                    upNext.addFirst(cur)
                    index = history.last()
                    index
                } else {
                    // 已在最早历史：停在当前（由服务层 seekTo(0) 处理）
                    index
                }
            }
        }
    }
}
