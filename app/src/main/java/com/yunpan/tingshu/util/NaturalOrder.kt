package com.yunpan.tingshu.util

import java.util.Comparator

/**
 * 文件名自然排序比较器（中文按 Unicode 码位、数字按数值）。
 *
 * 规则：把字符串切成「数字段」与「非数字段」交替的 token；
 *  - 数字段之间按数值大小比较（"第2回" < "第10回"）；
 *  - 非数字段之间按 Unicode 码位逐字符比较（中文即按 Unicode 排序）；
 *  - 数字段与非数字段相遇时按字符码位兜底。
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String?, b: String?): Int {
        if (a === b) return 0
        val sa = a ?: ""
        val sb = b ?: ""
        var i = 0
        var j = 0
        while (i < sa.length && j < sb.length) {
            val da = sa[i].isDigit()
            val db = sb[j].isDigit()
            val cmp: Int
            if (da && db) {
                // 收集两个数字段
                var iEnd = i
                while (iEnd < sa.length && sa[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < sb.length && sb[jEnd].isDigit()) jEnd++
                // 按数值比较（先比长度，再逐位），避免大数字转 Long 溢出
                cmp = compareNumberRuns(sa, i, iEnd, sb, j, jEnd)
                if (cmp != 0) return cmp
                i = iEnd
                j = jEnd
            } else {
                val ca = sa[i]
                val cb = sb[j]
                cmp = ca.compareTo(cb)
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        // 一个是另一个的前缀：短的在前
        return (sa.length - sb.length).let { if (it == 0) 0 else if (it < 0) -1 else 1 }
    }

    private fun compareNumberRuns(
        a: String, aStart: Int, aEnd: Int,
        b: String, bStart: Int, bEnd: Int
    ): Int {
        // 去掉前导 0 后比长度，长度相同再逐字符比
        var ai = aStart
        var bi = bStart
        while (ai < aEnd && a[ai] == '0') ai++
        while (bi < bEnd && b[bi] == '0') bi++
        val aLen = aEnd - ai
        val bLen = bEnd - bi
        if (aLen != bLen) return aLen - bLen
        for (k in 0 until aLen) {
            val c = a[ai + k].compareTo(b[bi + k])
            if (c != 0) return c
        }
        return 0
    }
}
