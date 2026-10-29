package com.com.android.ozjflash.term

import android.system.Os
import com.termux.app.TermuxInstaller
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 把 libtermux-bootstrap.so 里内嵌的 bootstrap.zip 解压成本 App 的 prefix。
 *
 * 流程与 Termux 上游 TermuxInstaller 一致：解压到 usr-staging → 建 SYMLINKS.txt 里的符号链接
 * → 补执行权限 → 整体 rename 成 usr。
 *
 * 额外多一步 fixup：bootstrap 是按 Termux 自己的包名编译的，二进制/脚本里写死了
 * /data/data/com.termux/files/usr。本 App 包名不同，所以把文本文件里的旧 prefix 全量替换成
 * 真实路径（脚本的 shebang、apt/dpkg 的配置都靠这一步才可用）。ELF 二进制里含 NUL 字节，跳过，
 * 它们靠 LD_LIBRARY_PATH 找库。
 */
object TermuxBootstrap {

    private const val PREFIX_NAME = "usr"
    private const val STAGING_NAME = "usr-staging"
    private const val STAMP_NAME = ".ozj-ready"

    /** bootstrap 编译时写死的 Termux 前缀 */
    private const val OLD_PREFIX = "/data/data/com.termux/files/usr"
    private const val OLD_HOME = "/data/data/com.termux/files/home"

    /** 0700：bootstrap 里可执行文件需要的权限 */
    private const val MODE_EXEC = 448

    fun prefixDir(filesDir: File): File = File(filesDir, PREFIX_NAME)

    fun homeDir(filesDir: File): File = File(filesDir, "home")

    /**
     * 首次启动时解压并修路径，之后直接返回已有 prefix。
     * 必须在子线程调用（要解压 29MB，几十秒内完成）。
     */
    fun installIfNeeded(filesDir: File, onProgress: (String) -> Unit): File {
        val prefix = prefixDir(filesDir)
        val stamp = File(prefix, STAMP_NAME)
        if (stamp.exists()) return prefix

        val staging = File(filesDir, STAGING_NAME)
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw RuntimeException("无法创建目录 ${staging.absolutePath}")

        onProgress("解压运行环境（约 29MB，请稍候）…")
        val symlinks = extractZip(staging)

        onProgress("修复路径与权限…")
        fixup(staging, prefix)
        createSymlinks(symlinks)

        onProgress("启用环境…")
        if (prefix.exists() && !prefix.deleteRecursively()) {
            throw RuntimeException("无法清理旧的 ${prefix.absolutePath}")
        }
        if (!staging.renameTo(prefix)) throw RuntimeException("重命名环境目录失败")

        writeShellProfiles(homeDir(filesDir), prefix)
        File(prefix, STAMP_NAME).writeText("ok")
        return prefix
    }

    /** 解压 zip，返回 (链接目标, 链接绝对路径) 列表 */
    private fun extractZip(staging: File): List<Pair<String, String>> {
        val zipBytes = TermuxInstaller.getZip()
        if (zipBytes.isEmpty()) throw RuntimeException("bootstrap 数据为空")
        val symlinks = ArrayList<Pair<String, String>>(1200)
        val buffer = ByteArray(16 * 1024)

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name

                if (name == "SYMLINKS.txt") {
                    zin.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (line.isNotBlank()) {
                            val parts = line.split("←")
                            if (parts.size != 2) throw RuntimeException("SYMLINKS 行格式错误: $line")
                            val linkPath = File(staging, parts[1])
                            linkPath.parentFile?.mkdirs()
                            symlinks.add(parts[0] to linkPath.absolutePath)
                        }
                    }
                    zin.closeEntry()
                    continue
                }

                val target = File(staging, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val n = zin.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    if (needsExecBit(name)) {
                        Os.chmod(target.absolutePath, MODE_EXEC)
                    }
                }
                zin.closeEntry()
            }
        }

        if (symlinks.isEmpty()) throw RuntimeException("bootstrap 内没有 SYMLINKS.txt")
        return symlinks
    }

    private fun needsExecBit(entryName: String): Boolean =
        entryName.startsWith("bin/") ||
            entryName.startsWith("libexec") ||
            entryName.startsWith("lib/apt/apt-helper") ||
            entryName.startsWith("lib/apt/methods")

    private fun createSymlinks(symlinks: List<Pair<String, String>>) {
        for ((target, linkPath) in symlinks) {
            val link = File(linkPath)
            if (link.exists() || link.parentFile?.exists() == true) {
                // 目标已存在（zip 里同名条目）时先删掉再建，避免 EEXIST
                runCatching { Os.unlink(linkPath) }
            }
            link.parentFile?.mkdirs()
            try {
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                // 个别链接失败不影响 bash 启动，记录下来继续
                e.printStackTrace()
            }
        }
    }

    /**
     * 把文本文件里的旧 Termux 前缀替换成真实前缀（原地改写，权限位不变）。
     * 只处理小于 4MB 且前 8KB 无 NUL 字节的文件，ELF/图片等二进制自动跳过。
     */
    private fun fixup(staging: File, prefix: File) {
        val oldBytes = OLD_PREFIX.toByteArray(Charsets.UTF_8)
        val newBytes = prefix.absolutePath.toByteArray(Charsets.UTF_8)
        val oldHomeBytes = OLD_HOME.toByteArray(Charsets.UTF_8)
        val newHomeBytes = File(prefix.parentFile, "home").absolutePath.toByteArray(Charsets.UTF_8)

        val stack = ArrayDeque<File>()
        stack.addLast(staging)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                    continue
                }
                if (!child.isFile || child.length() > 4L * 1024 * 1024) continue
                val content = try {
                    child.readBytes()
                } catch (e: Exception) {
                    continue
                }
                if (content.isEmpty()) continue
                val probe = if (content.size > 8192) content.copyOfRange(0, 8192) else content
                if (probe.any { it == 0.toByte() }) continue
                if (!contains(content, oldBytes) && !contains(content, oldHomeBytes)) continue

                val replaced = replace(replace(content, oldBytes, newBytes), oldHomeBytes, newHomeBytes)
                try {
                    FileOutputStream(child).use { it.write(replaced) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        var i = 0
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return true
            i++
        }
        return false
    }

    private fun replace(src: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        if (from.isEmpty()) return src
        val out = java.io.ByteArrayOutputStream(src.size + 1024)
        var i = 0
        while (i < src.size) {
            var matched = i + from.size <= src.size
            if (matched) {
                var j = 0
                while (j < from.size) {
                    if (src[i + j] != from[j]) {
                        matched = false
                        break
                    }
                    j++
                }
            }
            if (matched) {
                out.write(to)
                i += from.size
            } else {
                out.write(src[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    /** 首次启动写一份自己的 .bashrc/.bash_profile（bash 的编译期内置 profile 路径不存在） */
    private fun writeShellProfiles(home: File, prefix: File) {
        home.mkdirs()
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """
                # OZJFLASH 终端
                export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
                export PS1='\[\e[32m\]\w\[\e[0m\] \$ '
                alias ll='ls -alF'
                alias la='ls -A'
                export HISTFILE="${'$'}HOME/.bash_history"
                echo -e "\e[36mOZJFLASH\e[0m · $(uname -m) · 输入 exit 退出"
                """.trimIndent() + "\n"
            )
        }
        val profile = File(home, ".bash_profile")
        if (!profile.exists()) {
            profile.writeText("[ -f \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\"\n")
        }
    }
}
