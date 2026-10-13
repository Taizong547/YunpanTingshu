package com.yunpan.tingshu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountExpiryTest {

    private val now = 1_000_000_000_000L

    @Test
    fun unknown_when_zero() {
        assertTrue(AccountExpiry.evaluate(0L, now) is AccountExpiry.State.Unknown)
    }

    @Test
    fun expired_when_past() {
        assertTrue(AccountExpiry.evaluate(now - 1000, now) is AccountExpiry.State.Expired)
    }

    @Test
    fun expiring_soon_within_24h() {
        val s = AccountExpiry.evaluate(now + 12 * 3600_000L, now)
        assertTrue(s is AccountExpiry.State.ExpiringSoon)
    }

    @Test
    fun valid_when_more_than_24h() {
        val s = AccountExpiry.evaluate(now + 7 * 24 * 3600_000L, now)
        assertTrue(s is AccountExpiry.State.Valid)
    }
}
