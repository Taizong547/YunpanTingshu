package com.yunpan.tingshu.data.cover

import java.io.File

/**
 * 封面物理存储根（可注入，测试用临时目录）。真机根 = getExternalFilesDir(null)/covers/。
 * 物理文件命名 <coverId>.jpg（coverId = 压缩字节 SHA-256 前 16 位）。
 */
interface CoverStorage {
    fun rootDir(): File
    fun fileFor(coverId: String): File
    fun exists(coverId: String): Boolean
    fun write(coverId: String, bytes: ByteArray)
    fun delete(coverId: String): Boolean
    /** 建目录并放 .nomedia（阻止系统相册扫描）。 */
    fun ensureDir()
}

/** 文件型实现：直接落到给定根目录。 */
class DirCoverStorage(private val root: File) : CoverStorage {
    override fun rootDir(): File = root
    override fun fileFor(coverId: String): File = File(root, "$coverId.jpg")
    override fun exists(coverId: String): Boolean = fileFor(coverId).exists()
    override fun write(coverId: String, bytes: ByteArray) {
        ensureDir()
        fileFor(coverId).writeBytes(bytes)
    }
    override fun delete(coverId: String): Boolean = fileFor(coverId).delete()
    override fun ensureDir() {
        if (!root.exists()) root.mkdirs()
        val nomedia = File(root, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
    }
}
