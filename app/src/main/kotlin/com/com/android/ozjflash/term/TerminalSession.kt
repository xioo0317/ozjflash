package com.com.android.ozjflash.term

import android.content.Context
import android.os.ParcelFileDescriptor
import com.termux.terminal.JNI
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 一个 PTY 终端会话：JNI.createSubprocess 起 bash（登录 shell），
 * 主端 fd 包成流做读写，读线程增量 UTF-8 解码后喂给 TerminalScreen。
 */
class TerminalSession(private val ctx: Context) {

    val screen = TerminalScreen()

    @Volatile
    var running = false
        private set

    private var fd = -1
    private var pid = -1
    private var pfd: ParcelFileDescriptor? = null
    private var outStream: FileOutputStream? = null

    var onUpdate: (() -> Unit)? = null
    var onExit: ((String) -> Unit)? = null

    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    fun start(rows: Int, cols: Int): Boolean {
        if (running) return true
        val prefix = Bootstrap.prefix(ctx)
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) {
            onExit?.invoke("bootstrap 未安装")
            return false
        }
        val p = prefix.absolutePath
        val env = arrayOf(
            "HOME=${ctx.filesDir.absolutePath}/home",
            "PREFIX=$p",
            "TMPDIR=$p/tmp",
            "PATH=$p/bin",
            "LD_LIBRARY_PATH=$p/lib",
            "TERMINFO=$p/share/terminfo",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            "SHELL=${bash.absolutePath}",
            "PS1=ozj \\w \\$ ",
            "COLORTERM=truecolor"
        )
        val pidArr = IntArray(1)
        val newFd = try {
            JNI.createSubprocess("${bash.absolutePath} -l", p, env, pidArr, rows, cols)
        } catch (t: Throwable) {
            onExit?.invoke("创建子进程异常: ${t.message}")
            return false
        }
        if (newFd < 0) {
            onExit?.invoke("创建子进程失败(fd=$newFd)")
            return false
        }
        fd = newFd
        pid = pidArr[0]
        val desc = ParcelFileDescriptor.adoptFd(newFd)
        pfd = desc
        val inS = FileInputStream(desc.fileDescriptor)
        val outS = FileOutputStream(desc.fileDescriptor)
        outStream = outS
        try {
            JNI.setPtyUTF8Mode(fd, true)
            JNI.setPtyWindowSize(fd, rows, cols)
        } catch (_: Throwable) {
        }
        running = true

        Thread({ readLoop(inS) }, "term-read").apply { isDaemon = true; start() }
        Thread({
            val code = try {
                JNI.waitFor(pid)
            } catch (_: Throwable) {
                -1
            }
            running = false
            onExit?.invoke("进程已退出 (code=$code)")
        }, "term-wait").apply { isDaemon = true; start() }
        return true
    }

    private fun readLoop(inS: FileInputStream) {
        val buf = ByteArray(8192)
        var carry = ByteArray(0)
        try {
            while (true) {
                val n = inS.read(buf)
                if (n <= 0) break
                val all = ByteArray(carry.size + n)
                System.arraycopy(carry, 0, all, 0, carry.size)
                System.arraycopy(buf, 0, all, carry.size, n)
                val bb = ByteBuffer.wrap(all)
                val cb = CharBuffer.allocate(all.size + 16)
                decoder.reset()
                decoder.decode(bb, cb, false)
                cb.flip()
                val text = cb.toString()
                carry = ByteArray(bb.remaining())
                bb.get(carry)
                if (text.isNotEmpty()) {
                    screen.feed(text)
                    onUpdate?.invoke()
                }
            }
        } catch (_: Throwable) {
        }
        if (running) {
            running = false
            onExit?.invoke("连接已断开")
        }
    }

    @Synchronized
    fun write(s: String) {
        val os = outStream ?: return
        try {
            os.write(s.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        } catch (_: Throwable) {
        }
    }

    fun resize(rows: Int, cols: Int) {
        if (!running || fd < 0) return
        try {
            JNI.setPtyWindowSize(fd, rows, cols)
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            pfd?.close()
        } catch (_: Throwable) {
        }
        pfd = null
        outStream = null
        fd = -1
        pid = -1
    }
}
