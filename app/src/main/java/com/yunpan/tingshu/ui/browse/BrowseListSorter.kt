package com.yunpan.tingshu.ui.browse

import com.yunpan.tingshu.data.model.CloudFile

/** 列表排序维度。 */
enum class SortKey { NAME, SIZE, TIME }

/**
 * 浏览列表过滤 + 排序（纯逻辑，可单测）。
 * 文件夹始终排在文件前（沿用 foldersFirst），再按所选键排序。
 */
object BrowseListSorter {

    fun apply(items: List<CloudFile>, query: String, key: SortKey, ascending: Boolean): List<CloudFile> {
        val filtered = if (query.isBlank()) items
        else items.filter { it.name.contains(query.trim(), ignoreCase = true) }

        val dirs = filtered.filter { it.isDir }
        val files = filtered.filter { !it.isDir }

        val cmp: Comparator<CloudFile> = when (key) {
            SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortKey.SIZE -> compareBy { it.size }
            SortKey.TIME -> compareBy { it.updatedAtMs }
        }
        val dirsSorted = if (ascending) dirs.sortedWith(cmp) else dirs.sortedWith(cmp).reversed()
        val filesSorted = if (ascending) files.sortedWith(cmp) else files.sortedWith(cmp).reversed()
        return dirsSorted + filesSorted
    }
}
