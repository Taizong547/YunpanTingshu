package com.yunpan.tingshu.data.model

import com.yunpan.tingshu.data.model.MediaSpec

/**
 * 播放直链解析（供后续播放代理使用）。把一个 [CloudFile] 解析成可喂给 ExoPlayer 的 [MediaSpec]。
 *  - CLOUD139：取 active 账号调 getPlayUrl + 流头；isHls 按 URL 路径/扩展名(.m3u8)判定。
 *  - SHARE：直接用 presentURL(directUrl)，空请求头，isHls=true。
 *
 * v1.3 新增 [resolveFresh]：直链/分享鉴权过期后由播放层调用，强制绕过一切缓存重新解析
 * （SHARE 重新 openShare + 重新列目录取新鲜 presentURL；CLOUD139 重新 getPlayUrl）。
 */
interface PlaybackResolver {
    suspend fun resolve(file: CloudFile): MediaSpec

    /** 强制新鲜解析（默认与 [resolve] 相同；需要缓存失效的实现重写）。 */
    suspend fun resolveFresh(file: CloudFile): MediaSpec = resolve(file)
}
