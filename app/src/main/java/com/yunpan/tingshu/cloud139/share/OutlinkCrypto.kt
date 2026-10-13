package com.yunpan.tingshu.cloud139.share

import com.yunpan.tingshu.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 139 shareweb 出向加解密（照 share-api.md §4 / crypto/outlink_crypto.js）。
 *
 * 协议：HTTP body = Base64( IV(16B随机) ‖ AES-128-CBC-PKCS7( 明文JSON ) )
 *   key = "PVGDwmcvfs1uV3d1"（UTF8 16B → AES-128）；IV 每次随机 16B 前置；整体再 base64。
 * 响应用同样式解密；错误时服务端偶发明文 JSON，故 decrypt 失败需抛异常，由上层回退按明文错误 JSON 处理。
 */
object OutlinkCrypto {
    private val KEY = "PVGDwmcvfs1uV3d1".toByteArray(Charsets.UTF_8) // 16 bytes -> AES-128
    private const val TRANSFORM = "AES/CBC/PKCS5Padding"

    fun encrypt(plainJson: String): String {
        val iv = ByteArray(16).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return Base64.encode(combined)
    }

    /** 解密失败（密文损坏/被截断/非法填充）抛异常，由上层回退明文错误处理。 */
    fun decrypt(b64: String): String {
        val combined = Base64.decode(b64)
        if (combined.size < 16 + 16) throw IllegalArgumentException("密文过短")
        val iv = combined.copyOfRange(0, 16)
        val ct = combined.copyOfRange(16, combined.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(iv))
        val pt = cipher.doFinal(ct)
        return String(pt, Charsets.UTF_8)
    }

    private val rng = java.security.SecureRandom()
}
