package com.yunpan.tingshu.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获（v1.4）：把未捕获异常的线程/堆栈/设备信息写入 App 私有目录，
 * 然后交给系统默认 handler（进程照常死亡，不掩盖崩溃）。
 *
 * 日志位置：filesDir/crashes/crash-<时间戳>.txt，最多保留 [MAX_CRASH_FILES] 个。
 * "我的 → 查看崩溃日志"可读出最新内容（截图反馈即可精确定位）。
 */
class CrashHandler private constructor(
    private val ctx: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching { persist(t, e) }
        // 交回系统默认处理（弹出"屡次停止运行"并终止进程）
        defaultHandler?.uncaughtException(t, e)
    }

    private fun persist(thread: Thread, e: Throwable) {
        val dir = crashDir(ctx)
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$ts.txt")

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("线程: ${thread.name} (id=${thread.id})")
            pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("----------------------------------------")
            e.printStackTrace(pw)
        }
        file.writeText(sw.toString())
        prune(dir)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CRASH_FILES).forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val MAX_CRASH_FILES = 10

        fun crashDir(ctx: Context): File = File(ctx.filesDir, "crashes")

        /** 安装到当前进程（Application.onCreate 调用，幂等）。 */
        fun install(ctx: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(ctx.applicationContext, current))
        }

        /** 最新崩溃日志文本；无则 null。 */
        fun latest(ctx: Context): String? {
            val newest = crashDir(ctx).listFiles()
                ?.maxByOrNull { it.lastModified() } ?: return null
            return runCatching { newest.readText() }.getOrNull()
        }

        /** 全部崩溃日志（按时间倒序）。 */
        fun all(ctx: Context): List<File> =
            crashDir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
