package com.yunpan.tingshu.player

import com.yunpan.tingshu.util.Clock

/** 错误自愈决策结果。 */
sealed class RecoveryDecision {
    /** 应进行第 [attempt] 次重试（退避 [backoffMs] 后重取直链）。 */
    data class Retry(val attempt: Int, val backoffMs: Long) : RecoveryDecision()

    /** 超过最大重试次数，转中文错误态、不闪退。 */
    data object GiveUp : RecoveryDecision()
}

/**
 * 播放错误自动续播（纯逻辑，注入 Clock 可单测）。
 *
 * 触发：PlaybackException / HTTP 403 / loader IO / 缓冲中断。
 * 流程：记录失败 → 指数退避 1/2/4/8s（最多 [maxRetries]=4 次）→ 重新取直链 →
 * 替换 MediaSource → seekTo(原位置) → 原在播则续播。成功清零；超限转错误态。
 *
 * 真正的“重取直链 + seek”由 Engine 执行（需 ExoPlayer/resolver），本类只决定退避与计数。
 */
class PlaybackRecoverer(
    private val clock: Clock,
    private val maxRetries: Int = 4,
    private val baseBackoffMs: Long = 1000L
) {
    private var attempts: Int = 0

    /** 发生一次错误。返回下一步决策。 */
    fun noteError(): RecoveryDecision {
        attempts += 1
        return if (attempts > maxRetries) {
            RecoveryDecision.GiveUp
        } else {
            RecoveryDecision.Retry(attempt = attempts, backoffMs = backoffMsFor(attempts))
        }
    }

    /** 第 n 次重试前的退避：1,2,4,8s。 */
    fun backoffMsFor(n: Int): Long = baseBackoffMs shl (n - 1)

    /** 恢复成功，清零计数。 */
    fun noteSuccess() { attempts = 0 }

    fun currentAttempt(): Int = attempts

    fun isExhausted(): Boolean = attempts > maxRetries

    /** 重置（切歌/用户手动换源时）。 */
    fun reset() { attempts = 0 }
}
