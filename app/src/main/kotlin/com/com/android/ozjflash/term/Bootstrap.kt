package com.com.android.ozjflash.term

import android.content.Context
import com.termux.app.TermuxInstaller
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 首次启动把 libtermux-bootstrap.so 内嵌的 bootstrap.zip 解压到 filesDir/usr，
 * 按 SYMLINKS.txt 重建符号链接（格式: target←link），并给可执行文件加执行位。
 */
object Bootstrap {

    fun prefix(ctx: Context): File = File(ctx.filesDir, "usr")

    fun isInstalled(ctx: Context): Boolean = File(prefix(ctx), "bin/bash").exists()

    /** 阻塞式解压，请在 IO 线程调用。onProgress(已写字节, 总字节) */
    fun install(ctx: Context, onProgress: (Long, Long) -> Unit) {
        val usr = prefix(ctx)
        if (usr.exists()) usr.deleteRecursively()
        usr.mkdirs()

        val zip = TermuxInstaller.getZip()
        val total = zip.size.toLong()
        var written = 0L

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val target = File(usr, name)
                if (name.endsWith("/")) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            written += n
                            if (written / (2L * 1024 * 1024) != (written - n) / (2L * 1024 * 1024)) {
                                onProgress(written, total)
                            }
                        }
                    }
                    if (needsExec(name, target)) target.setExecutable(true, false)
                }
                entry = zis.nextEntry
            }
        }

        // 符号链接（zip 存不下 symlink，Termux 用 SYMLINKS.txt 记录）
        val links = File(usr, "SYMLINKS.txt")
        if (links.exists()) {
            links.readLines().forEach { line ->
                val idx = line.indexOf('\u2190') // ←
                if (idx <= 0) return@forEach
                val linkTarget = line.substring(0, idx)
                val linkPath = line.substring(idx + 1)
                if (linkPath.isEmpty()) return@forEach
                val linkFile = File(usr, linkPath)
                linkFile.parentFile?.mkdirs()
                try {
                    if (!linkFile.exists()) {
                        android.system.Os.symlink(linkTarget, linkFile.absolutePath)
                    }
                } catch (_: Throwable) {
                    // 已存在或平台限制，跳过
                }
            }
        }

        File(usr, "tmp").mkdirs()
        File(ctx.filesDir, "home").mkdirs()
        onProgress(total, total)
    }

    private fun needsExec(name: String, f: File): Boolean {
        if (name.startsWith("bin/") || name.startsWith("libexec/") || name.contains("/apt/methods/")) return true
        return try {
            val b = ByteArray(4)
            f.inputStream().use { it.read(b) }
            b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        } catch (_: Throwable) {
            false
        }
    }
}
