package com.termux.terminal

/**
 * 对应 libtermux.so 的原生接口（Termux 官方 PTY 子进程实现）。
 * 符号名 Java_com_termux_terminal_JNI_*，签名经反汇编核对：
 *   createSubprocess(cmd, cwd, env[], processId[1], rows, columns) -> pty master fd
 */
object JNI {
    init {
        System.loadLibrary("termux")
    }

    @JvmStatic
    external fun createSubprocess(
        cmd: String,
        cwd: String,
        env: Array<String>,
        processId: IntArray,
        rows: Int,
        columns: Int
    ): Int

    @JvmStatic
    external fun setPtyWindowSize(fd: Int, rows: Int, columns: Int)

    @JvmStatic
    external fun setPtyUTF8Mode(fd: Int, utf8Mode: Boolean)

    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun close(fd: Int)
}
