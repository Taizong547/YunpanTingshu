package com.yunpan.tingshu.cloud139

import com.yunpan.tingshu.util.Rand
import com.yunpan.tingshu.util.TimeFmt

/**
 * 139 请求头（对应 TS cloud139Headers，常量照 constants.json / SPEC §3.3 / auth.ts:51-86）。
 * 每个请求生成新的 ts/rand/sign。route=true 时不带 x-yun-* / Caller / mcloud-route。
 */
object Cloud139Headers {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val ROUTE_POLICY_URL = "https://user-njs.yun.139.com/user/route/qryRoutePolicy"
    const val DEVICE_INFO = "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||"
    const val CLIENT_INFO = "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||dW5kZWZpbmVk||"

    /** 业务错误码：命中即视为失败。 */
    val ERROR_CODES = setOf("9000", "9008", "9100")

    /**
     * 构造一套请求头。
     * @param rawToken  去 Basic 后的 base64 原文
     * @param body      明文请求体 JSON 字符串（签名对象）
     * @param route     true = qryRoutePolicy 路由请求（不带 x-yun-* / Caller / mcloud-route）
     * @param svcType   x-SvcType / x-yun-svc-type，默认 "1"
     * @param ts        可注入（测试用）；默认东八区当前时间
     * @param rand      可注入（测试用）；默认 16 位随机
     */
    fun build(
        rawToken: String,
        body: String,
        route: Boolean = false,
        svcType: String = "1",
        ts: String = TimeFmt.nowEast8(),
        rand: String = Rand.next16()
    ): Map<String, String> {
        val sign = Cloud139Signer.sign(body, ts, rand)
        val headers = linkedMapOf(
            "Accept" to "application/json, text/plain, */*",
            "CMS-DEVICE" to "default",
            "Content-Type" to "application/json;charset=UTF-8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "User-Agent" to USER_AGENT,
            "Origin" to "https://yun.139.com",
            "Referer" to "https://yun.139.com/w/",
            "x-DeviceInfo" to DEVICE_INFO,
            "x-huawei-channelSrc" to "10000034",
            "x-inner-ntwk" to "2",
            "x-m4c-caller" to "PC",
            "x-m4c-src" to "10002",
            "Inner-Hcy-Router-Https" to "1",
            "x-SvcType" to svcType,
            "Authorization" to "Basic $rawToken",
            "mcloud-channel" to "1000101",
            "mcloud-client" to "10701",
            "mcloud-sign" to "$ts,$rand,$sign",
            "mcloud-version" to "7.14.0"
        )
        if (!route) {
            headers["Caller"] = "web"
            headers["mcloud-route"] = "001"
            headers["x-yun-api-version"] = "v1"
            headers["x-yun-app-channel"] = "10000034"
            headers["x-yun-channel-source"] = "10000034"
            headers["x-yun-client-info"] = CLIENT_INFO
            headers["x-yun-module-type"] = "100"
            headers["x-yun-svc-type"] = svcType
        }
        return headers
    }

    /** 播放/下载个人云盘直链必须带的流头（SPEC §3.7）。 */
    fun streamHeaders(): Map<String, String> = linkedMapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://yun.139.com/",
        "Origin" to "https://yun.139.com"
    )
}
