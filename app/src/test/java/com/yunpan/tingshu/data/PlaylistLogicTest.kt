package com.yunpan.tingshu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistLogicTest {

    @Test
    fun name_trimmed_and_valid() {
        assertEquals("言情小说", PlaylistLogic.normalizeName("  言情小说  "))
    }

    @Test
    fun name_null_or_blank_rejected() {
        assertNull(PlaylistLogic.normalizeName(null))
        assertNull(PlaylistLogic.normalizeName("   "))
        assertNull(PlaylistLogic.normalizeName(""))
    }

    @Test
    fun name_truncated_to_20() {
        val long = "一".repeat(30)
        assertEquals(20, PlaylistLogic.normalizeName(long)?.length)
    }
}
