package com.yunpan.tingshu.ui.common

/** 从目录条目里筛出图片文件（按扩展名，纯逻辑可单测）。 */
object ImageFiles {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun isImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXT
    }

    /** 分享目录里 coType=1 的图片：thumbnailUrl 非空且非目录。 */
    fun filter(names: List<String>): List<String> = names.filter { isImageName(it) }
}
