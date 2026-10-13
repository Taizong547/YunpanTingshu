package com.yunpan.tingshu.ui.common

import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.ScannedTrack
import com.yunpan.tingshu.util.NaturalOrder

/**
 * 浏览列表归并 reducer（纯 JVM，可单测）。
 * - [foldersFirst]：文件夹在前、音频在后，各自按文件名自然排序。
 * - [audioOnly]：仅当前层音频（点击某音频时以此构建播放队列）。
 * - [appendPage]：翻页追加，按 id 去重。
 * - [toScannedTracks]：当前层音频包成 ScannedTrack（albumPath=当前文件夹名）。
 */
object BrowseReducer {

    fun foldersFirst(items: List<CloudFile>): List<CloudFile> {
        val folders = items.filter { it.isDir }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        val audio = items.filter { !it.isDir && it.isAudio }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        return folders + audio
    }

    fun audioOnly(items: List<CloudFile>): List<CloudFile> =
        items.filter { !it.isDir && it.isAudio }

    fun appendPage(current: List<CloudFile>, page: List<CloudFile>): List<CloudFile> {
        if (page.isEmpty()) return current
        val seen = HashSet(current.map { it.id })
        val added = page.filter { seen.add(it.id) }
        return if (added.isEmpty()) current else current + added
    }

    /** 当前层音频 → ScannedTrack，albumPath 为当前文件夹名（用于播放队列/专辑标题）。 */
    fun toScannedTracks(currentFolderName: String, audio: List<CloudFile>): List<ScannedTrack> =
        audio.map { ScannedTrack(it.withAlbumPath(listOf(currentFolderName)), listOf(currentFolderName)) }
}
