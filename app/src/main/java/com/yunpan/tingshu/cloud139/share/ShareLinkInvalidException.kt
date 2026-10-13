package com.yunpan.tingshu.cloud139.share

import java.io.IOException

/**
 * 分享链接无效/过期/密码错误异常。
 *
 * 触发场景：
 * - 链接已失效（被分享者删除或过期）
 * - 提取码错误
 * - 链接不存在
 *
 * UI层应接住此异常，给出明确的中文提示。
 */
class ShareLinkInvalidException(
    message: String,
    val errorCode: String? = null
) : IOException(message)
