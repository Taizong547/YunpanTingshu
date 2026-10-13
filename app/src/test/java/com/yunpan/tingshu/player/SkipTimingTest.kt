package com.yunpan.tingshu.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 智能跳过片头/片尾纯逻辑单测。 */
class SkipTimingTest {

    @Test
    fun intro_skip_when_fresh_start() {
        // 从头播（resume=0）且配置 30s → seek 到 30s
        assertEquals(30_000L, SkipTiming.introSeekMs(0L, 30_000L))
    }

    @Test
    fun intro_no_skip_when_resuming_mid() {
        // 有断点位置（如 5min）→ 不跳，直接续播
        assertEquals(300_000L, SkipTiming.introSeekMs(300_000L, 30_000L))
    }

    @Test
    fun intro_noop_when_config_zero() {
        assertEquals(0L, SkipTiming.introSeekMs(0L, 0L))
        assertEquals(12_000L, SkipTiming.introSeekMs(12_000L, 0L))
    }

    @Test
    fun intro_negative_resume_treated_as_zero() {
        assertEquals(15_000L, SkipTiming.introSeekMs(-500L, 15_000L))
    }

    @Test
    fun outro_triggers_when_inside_window() {
        // 总时长 10min，剩余 20s，片尾跳过 30s → 触发
        assertTrue(SkipTiming.shouldAutoNext(580_000L, 600_000L, 30_000L))
        // 正好剩余 30s 边界 → 触发
        assertTrue(SkipTiming.shouldAutoNext(570_000L, 600_000L, 30_000L))
    }

    @Test
    fun outro_no_trigger_when_far_from_end() {
        assertFalse(SkipTiming.shouldAutoNext(100_000L, 600_000L, 30_000L))
    }

    @Test
    fun outro_noop_when_config_zero_or_unknown_duration() {
        assertFalse(SkipTiming.shouldAutoNext(580_000L, 600_000L, 0L))
        assertFalse(SkipTiming.shouldAutoNext(580_000L, 0L, 30_000L))
    }

    @Test
    fun presets_are_standard() {
        assertEquals(listOf(0, 15, 30, 60, 90), SkipTiming.PRESETS_SEC)
    }
}
