package com.yunpan.tingshu.cloud139.share

/**
 * 从任意粘贴文本中提取 139 分享链接和提取码（纯逻辑，可单测）。
 *
 * 真实粘贴文案形如：
 *   链接: https://yun.139.com/shareweb/#/w/i/2ygbbRFet7bws
 *   提取码: s1ak
 *   复制内容打开移动云盘PC客户端，操作更方便哦
 * 本对象从中单独抽出 URL 与提取码，丢弃其它无关文字。
 */
data class ShareParseResult(
    val link: String?,
    val passcode: String?
)

object ShareLinkExtractor {

    /** 匹配 yun.139.com 的分享路径：/w/i/xxxx 或 /shareweb/#/w/i/xxxx。 */
    private val URL_REGEX = Regex(
        "(https?://)?(yun\\.139\\.com/(?:shareweb/#/)?w/i/[A-Za-z0-9_-]+)",
        RegexOption.IGNORE_CASE
    )

    /** 兜底：裸 /w/i/xxxx 或 /i/xxxx。 */
    private val PATH_REGEX = Regex("(/w)?/i/([A-Za-z0-9_-]+)")

    /** "提取码: xxxx" / "提取码：xxxx" / "密码: xxxx" / "pwd: xxx" / "passwd: xxx"（必须带前缀）。 */
    private val LABELED_CODE_REGEX = Regex(
        "(?:提取码|密码|访问码|pwd|passwd)[\\s:：=]*([A-Za-z0-9]{4,8})",
        RegexOption.IGNORE_CASE
    )

    /**
     * 解析整段粘贴文本，返回(链接, 提取码)。链接提取不到为 null；提取码只在带明确前缀时才返回，
     * 找不到就为 null（绝不从链接/无关文字里瞎猜 4 位串，避免把 https/http/yun 误判成提取码）。
     */
    fun parse(input: String?): ShareParseResult {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return ShareParseResult(null, null)

        val link = URL_REGEX.find(s)?.let { "https://" + it.groupValues[2].trimEnd('/', '，', ',', '。') }
            ?: PATH_REGEX.find(s)?.let { "https://yun.139.com/w/i/${it.groupValues[2]}" }

        // 只认"提取码/密码/访问码/pwd/passwd: xxxx"这类带明确前缀的
        val code = LABELED_CODE_REGEX.find(s)?.groupValues?.getOrNull(1)

        return ShareParseResult(link, code)
    }

    /** 仅返回链接（保持旧调用兼容）。 */
    fun extract(input: String?): String? = parse(input).link
}
