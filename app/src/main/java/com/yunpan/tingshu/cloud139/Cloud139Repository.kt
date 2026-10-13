package com.yunpan.tingshu.cloud139

import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.ListPage
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.data.model.ScannedTrack
import com.yunpan.tingshu.data.model.ScanProgress

/**
 * 个人云盘 139 仓库接口（数据层实现，界面/播放层只依赖此接口，可用 fake 开发）。
 */
interface Cloud139Repository {
    /** 列一页目录。parentId 根目录传 "/"；cursor 为空表示第一页。 */
    suspend fun listFolder(account: CloudAccount, parentId: String = "/", cursor: String = ""): ListPage

    /** 自动翻页取全该文件夹内音频（翻页直到 nextCursor 空，带上限保护）。 */
    suspend fun listAllAudio(account: CloudAccount, folderId: String): List<CloudFile>

    /** 取个人云盘文件播放直链（cdnUrl/url/downloadUrl）。 */
    suspend fun getPlayUrl(account: CloudAccount, fileId: String): String

    /** 播放/下载个人云盘直链必须带的请求头（UA/Referer/Origin）。 */
    fun streamHeaders(): Map<String, String>

    /** 退出登录/换号时清空路由 host 缓存（默认空实现，便于 fake）。 */
    fun clearHostCache() {}

    /**
     * 递归扫描某文件夹下的音频（BFS，按游标翻页拉全每个文件夹）。
     * @param rootFolderId 根文件夹（""/"0"/"cloud139_root" 视为根 "/"）
     * @param depth        扫描深度
     * @param progress     进度回调（可空）
     * @return 按 albumPath 分组、组内按文件名自然排序后扁平化的音频列表；每条 file.albumPath 已填相对目录分段
     */
    suspend fun scanAudio(
        account: CloudAccount,
        rootFolderId: String,
        depth: ScanDepth,
        progress: ((ScanProgress) -> Unit)? = null
    ): List<ScannedTrack>
}
