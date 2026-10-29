package com.termux.app

/**
 * 对应 libtermux-bootstrap.so 的 JNI：返回内嵌在 .rodata 里的 bootstrap.zip 全部字节。
 * 符号名 Java_com_termux_app_TermuxInstaller_getZip。
 */
object TermuxInstaller {
    init {
        System.loadLibrary("termux-bootstrap")
    }

    @JvmStatic
    external fun getZip(): ByteArray
}
