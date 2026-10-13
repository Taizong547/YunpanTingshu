package com.yunpan.tingshu.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRecovererTest {

    private val clock = VirtualClock()
    private val recoverer = PlaybackRecoverer(clock, maxRetries = 4)

    @Test fun backoffSequenceIs1_2_4_8s() {
        assertThat(recoverer.backoffMsFor(1)).isEqualTo(1_000L)
        assertThat(recoverer.backoffMsFor(2)).isEqualTo(2_000L)
        assertThat(recoverer.backoffMsFor(3)).isEqualTo(4_000L)
        assertThat(recoverer.backoffMsFor(4)).isEqualTo(8_000L)
    }

    @Test fun fourRetriesThenGiveUp() {
        repeat(4) { i ->
            val d = recoverer.noteError()
            check(d is RecoveryDecision.Retry)
            assertThat(d.attempt).isEqualTo(i + 1)
        }
        // 第 5 次错误 → GiveUp
        assertThat(recoverer.noteError()).isEqualTo(RecoveryDecision.GiveUp)
        assertThat(recoverer.isExhausted()).isTrue()
    }

    @Test fun successResetsCounter() {
        recoverer.noteError()
        recoverer.noteError()
        recoverer.noteSuccess()
        val d = recoverer.noteError()
        assertThat(d).isEqualTo(RecoveryDecision.Retry(1, 1_000L))
    }

    @Test fun resetClearsState() {
        recoverer.noteError(); recoverer.noteError()
        recoverer.reset()
        assertThat(recoverer.currentAttempt()).isEqualTo(0)
    }
}
