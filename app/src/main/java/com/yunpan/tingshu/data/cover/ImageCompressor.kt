package com.yunpan.tingshu.data.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 图片压缩器（可注入，测试用 fake 避免依赖 BitmapFactory）。
 * 实现：居中裁 512x512 → JPEG q85；单图超 ~200KB 时逐步降质量。
 */
interface ImageCompressor {
    /** 输入原始图片字节，输出最终 JPEG 字节（用于哈希与落地）。 */
    fun compress(src: ByteArray): ByteArray
}

/** 真机实现（BitmapFactory）。 */
object RealImageCompressor : ImageCompressor {
    private const val SIZE = 512
    private const val MAX_BYTES = 200L * 1024

    override fun compress(src: ByteArray): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, opts)
        var sample = 1
        val (w, h) = opts.outWidth to opts.outHeight
        while (w / sample > SIZE * 2 || h / sample > SIZE * 2) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size, decodeOpts)
            ?: return src  // 解码失败，原样返回（上层会哈希去重）

        // 居中裁正方形
        val side = minOf(bmp.width, bmp.height)
        val x = (bmp.width - side) / 2
        val y = (bmp.height - side) / 2
        val squared = Bitmap.createBitmap(bmp, x, y, side, side)
        val scaled = if (side != SIZE) Bitmap.createScaledBitmap(squared, SIZE, SIZE, true) else squared

        var quality = 85
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            out = bos.toByteArray()
            quality -= 10
        } while (out.size > MAX_BYTES && quality > 40)
        return out
    }
}
