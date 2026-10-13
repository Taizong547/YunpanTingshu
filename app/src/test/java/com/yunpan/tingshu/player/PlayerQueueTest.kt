package com.yunpan.tingshu.player

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import org.junit.Test

class PlayerQueueTest {

    private fun files(n: Int): List<CloudFile> = (0 until n).map {
        CloudFile(
            id = "f$it", name = "f$it.mp3", isDir = false, size = 0, updatedAtMs = 0,
            thumbnailUrl = "", parentId = "root", audioExt = "mp3", source = FileSource.CLOUD139
        )
    }

    @Test fun sequentialNextWalksToEndAndStops() {
        val q = PlayerQueue()
        q.setList(files(3), 0)
        assertThat(q.index).isEqualTo(0)
        assertThat(q.next()).isEqualTo(1)
        assertThat(q.next()).isEqualTo(2)
        assertThat(q.next()).isNull() // 末集 next = null 停止
        assertThat(q.index).isEqualTo(2)
    }

    @Test fun sequentialPrevStopsAtFirst() {
        val q = PlayerQueue()
        q.setList(files(3), 2)
        assertThat(q.prev()).isEqualTo(1)
        assertThat(q.prev()).isEqualTo(0)
        assertThat(q.prev()).isNull() // 首集 prev = null
        assertThat(q.index).isEqualTo(0)
    }

    @Test fun repeatOneDoesNotAdvance() {
        val q = PlayerQueue()
        q.setList(files(3), 1)
        q.setMode(PlayMode.REPEAT_ONE)
        repeat(3) {
            assertThat(q.next()).isEqualTo(1)
            assertThat(q.prev()).isEqualTo(1)
        }
        assertThat(q.index).isEqualTo(1)
    }

    @Test fun shuffleDoesNotRepeatUntilExhaustedAndStaysInBounds() {
        val q = PlayerQueue()
        val n = 8
        q.setList(files(n), 0)
        q.setMode(PlayMode.SHUFFLE)
        val seen = LinkedHashSet<Int>()
        seen.add(q.index) // 当前首集已计入
        // 一轮：再连取 n-1 次 next，应覆盖全部 n 个且不重复
        repeat(n - 1) {
            val t = q.next()!!
            assertThat(t).isIn(0 until n)
            check(seen.add(t)) { "SHUFFLE 不应在一轮内重复: $t" }
        }
        assertThat(seen).hasSize(n)
        // 再取多轮：不越界、不连续重复当前
        var prev = q.index
        repeat(40) {
            val t = q.next()!!
            assertThat(t).isIn(0 until n)
            check(t != prev) { "SHUFFLE 不应连续重复当前: $t" }
            prev = t
        }
    }

    @Test fun shufflePrevGoesBack() {
        val q = PlayerQueue()
        q.setList(files(5), 0)
        q.setMode(PlayMode.SHUFFLE)
        val first = q.next()!!
        val second = q.next()!!
        assertThat(q.prev()).isEqualTo(first)
        // 再 prev 回到起点
        assertThat(q.prev()).isEqualTo(0)
        // 再 next 仍可继续
        val again = q.next()!!
        assertThat(again).isIn(0 until 5)
    }

    @Test fun moveToJumpsAndBounds() {
        val q = PlayerQueue()
        q.setList(files(4), 0)
        assertThat(q.moveTo(3)).isTrue()
        assertThat(q.index).isEqualTo(3)
        assertThat(q.moveTo(99)).isFalse()
        assertThat(q.moveTo(-1)).isFalse()
    }

    @Test fun setModeToShuffleReshufflesPreservingCurrent() {
        val q = PlayerQueue()
        q.setList(files(5), 2)
        q.setMode(PlayMode.SHUFFLE)
        assertThat(q.current()!!.id).isEqualTo("f2")
        // 一轮内（其余 4 首）next 不会立即回到当前
        repeat(4) {
            assertThat(q.next()).isNotEqualTo(2)
        }
    }

    @Test fun emptyQueueBehaves() {
        val q = PlayerQueue()
        assertThat(q.current()).isNull()
        assertThat(q.next()).isNull()
        assertThat(q.prev()).isNull()
        assertThat(q.size).isEqualTo(0)
    }

    @Test fun startIndexCoerced() {
        val q = PlayerQueue()
        q.setList(files(3), 99)
        assertThat(q.index).isEqualTo(2)
    }
}
