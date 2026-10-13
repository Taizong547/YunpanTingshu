package com.yunpan.tingshu.ui.browse

import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseListSorterTest {

    private fun f(name: String, isDir: Boolean = false, size: Long = 0, time: Long = 0) =
        CloudFile(
            id = name, name = name, isDir = isDir, size = size, updatedAtMs = time,
            thumbnailUrl = "", parentId = "", audioExt = null, source = FileSource.CLOUD139
        )

    @Test
    fun folders_first_then_name() {
        val items = listOf(f("b.mp3"), f("文件夹A", isDir = true), f("a.mp3"), f("文件夹B", isDir = true))
        val r = BrowseListSorter.apply(items, "", SortKey.NAME, true)
        assertEquals(listOf("文件夹A", "文件夹B", "a.mp3", "b.mp3"), r.map { it.name })
    }

    @Test
    fun descending_name() {
        val items = listOf(f("a.mp3"), f("b.mp3"))
        val r = BrowseListSorter.apply(items, "", SortKey.NAME, false)
        assertEquals(listOf("b.mp3", "a.mp3"), r.map { it.name })
    }

    @Test
    fun filter_by_query_chinese_contains() {
        val items = listOf(f("三国演义01"), f("红楼梦02"), f("三国续集"))
        val r = BrowseListSorter.apply(items, "三国", SortKey.NAME, true)
        assertEquals(listOf("三国演义01", "三国续集"), r.map { it.name })
    }

    @Test
    fun sort_by_time_descending() {
        val items = listOf(f("old", time = 100), f("new", time = 999))
        val r = BrowseListSorter.apply(items, "", SortKey.TIME, false)
        assertEquals(listOf("new", "old"), r.map { it.name })
    }

    @Test
    fun blank_query_returns_all() {
        val items = listOf(f("x"))
        assertTrue(BrowseListSorter.apply(items, "  ", SortKey.NAME, true).isNotEmpty())
    }
}
