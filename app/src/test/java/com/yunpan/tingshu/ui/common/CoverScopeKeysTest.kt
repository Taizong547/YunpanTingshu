package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.cover.CoverStore
import org.junit.Test

class CoverScopeKeysTest {
    @Test fun `file scope targets file id`() {
        val (scope, keys) = CoverScopeKeys.resolve(CoverScope.THIS_FILE, "f1", "albumA", listOf("albumA"))
        assertThat(scope).isEqualTo(CoverStore.SCOPE_FILE)
        assertThat(keys).containsExactly("f1")
    }
    @Test fun `album scope targets album key`() {
        val (scope, keys) = CoverScopeKeys.resolve(CoverScope.THIS_ALBUM, "f1", "albumA", emptyList())
        assertThat(scope).isEqualTo(CoverStore.SCOPE_ALBUM)
        assertThat(keys).containsExactly("albumA")
    }
    @Test fun `blank album key falls back to file id`() {
        val (_, keys) = CoverScopeKeys.resolve(CoverScope.THIS_ALBUM, "f1", "", emptyList())
        assertThat(keys).containsExactly("f1")
    }
    @Test fun `queue scope dedupes album keys`() {
        val (scope, keys) = CoverScopeKeys.resolve(
            CoverScope.QUEUE, "f1", "albumA", listOf("albumA", "albumA", "albumB", "")
        )
        assertThat(scope).isEqualTo(CoverStore.SCOPE_ALBUM)
        assertThat(keys).containsExactly("albumA", "albumB").inOrder()
    }
    @Test fun `empty queue falls back to album`() {
        val (_, keys) = CoverScopeKeys.resolve(CoverScope.QUEUE, "f1", "albumA", listOf(""))
        assertThat(keys).containsExactly("albumA")
    }
}

class SleepWheelTest {
    @Test fun `hours and minutes combine`() {
        assertThat(SleepWheel.toMinutes(1, 30)).isEqualTo(90)
        assertThat(SleepWheel.toMinutes(0, 45)).isEqualTo(45)
    }
    @Test fun `zero coerces to 1`() {
        assertThat(SleepWheel.toMinutes(0, 0)).isEqualTo(1)
    }
    @Test fun `clamp to max`() {
        // 时上限3、分上限59 → 最大 239 分钟
        assertThat(SleepWheel.toMinutes(5, 99)).isEqualTo(239)
    }
    @Test fun `episodes clamped`() {
        assertThat(SleepWheel.episodes(0)).isEqualTo(1)
        assertThat(SleepWheel.episodes(99)).isEqualTo(50)
        assertThat(SleepWheel.episodes(5)).isEqualTo(5)
    }
}
