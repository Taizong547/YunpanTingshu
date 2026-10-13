package com.yunpan.tingshu.cloud139

import java.io.IOException

/**
 * Token过期/无效异常。
 *
 * 触发场景：
 * - 业务错误码 9008（Token已过期）
 * - HTTP 401/403 未授权
 * - 错误码 9100（权限不足，大概率是token失效）
 *
 * UI层应接住此异常，提示用户"登录已过期，请重新登录"，
 * 而不是让异常冒泡导致闪退或显示模糊的网络错误。
 */
class TokenExpiredException(
    message: String,
    val isHttpAuthError: Boolean = false
) : IOException(message)
