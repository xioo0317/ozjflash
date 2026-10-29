package com.com.android.ozjflash

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.com.android.ozjflash.term.TermuxBootstrap
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * OZJFLASH 终端主界面。
 *
 * 终端本体完全使用 Termux 上游代码：
 *  - com.termux.terminal.*（terminal-emulator 模块，含 libtermux.so 源码编译）
 *  - com.termux.view.TerminalView（terminal-view 模块）
 * 本文件只负责：解压 bootstrap、按需创建 bash 会话、软键盘 + 一排功能键。
 */
class MainActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private lateinit var statusView: TextView
    private lateinit var ctrlKey: Button
    private lateinit var altKey: Button

    private var session: TerminalSession? = null
    private var ctrlDown = false
    private var altDown = false
    private var fontSizePx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (t: Throwable) {
            showFatal("界面初始化失败", t)
            return
        }
        Thread({ startSession() }, "ozj-bootstrap").start()
    }

    override fun onDestroy() {
        runCatching { session?.finishIfRunning() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0B0B.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(4), dp(3))
        }
        statusView = TextView(this).apply {
            text = "准备运行环境…"
            setTextColor(0xFF8BC34A.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        bar.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(smallButton("⌨") { showKeyboard() })
        bar.addView(smallButton("↻") { restartSession() })
        root.addView(bar)

        fontSizePx = dp(13)
        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(this@MainActivity)
            setTextSize(fontSizePx)
            setBackgroundColor(0xFF000000.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildKeyRow())

        setContentView(root)
    }

    private fun buildKeyRow(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF121212.toInt())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        ctrlKey = keyButton("CTRL") { toggleCtrl() }
        altKey = keyButton("ALT") { toggleAlt() }

        row.addView(keyButton("ESC") { sendBytes(byteArrayOf(27)) })
        row.addView(keyButton("TAB") { sendBytes(byteArrayOf(9)) })
        row.addView(ctrlKey)
        row.addView(altKey)
        row.addView(keyButton("←") { sendText("\u001b[D") })
        row.addView(keyButton("↑") { sendText("\u001b[A") })
        row.addView(keyButton("↓") { sendText("\u001b[B") })
        row.addView(keyButton("→") { sendText("\u001b[C") })
        row.addView(keyButton("^C") { sendBytes(byteArrayOf(3)) })
        row.addView(keyButton("^D") { sendBytes(byteArrayOf(4)) })
        row.addView(keyButton("^L") { sendBytes(byteArrayOf(12)) })
        row.addView(keyButton("^Z") { sendBytes(byteArrayOf(26)) })
        row.addView(keyButton("PASTE") { pasteFromClipboard() })
        row.addView(keyButton("⏎") { sendBytes(byteArrayOf(13)) })

        scroll.addView(row)
        return scroll
    }

    private fun smallButton(label: String, onTap: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setTextColor(0xFFBDBDBD.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF1C1C1C.toInt())
            }
            setOnClickListener { onTap() }
        }

    private fun keyButton(label: String, onTap: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minWidth = dp(46)
            minimumWidth = dp(46)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(0xFFE0E0E0.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF242424.toInt())
                setStroke(dp(1).coerceAtLeast(1), 0xFF3A3A3A.toInt())
            }
            setOnClickListener { onTap() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(4)
            layoutParams = lp
        }

    private fun toggleCtrl() {
        ctrlDown = !ctrlDown
        markToggle(ctrlKey, ctrlDown)
    }

    private fun toggleAlt() {
        altDown = !altDown
        markToggle(altKey, altDown)
    }

    private fun markToggle(button: Button, on: Boolean) {
        (button.background as? GradientDrawable)?.setColor(
            if (on) 0xFF2E7D32.toInt() else 0xFF242424.toInt()
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    // ------------------------------------------------------------ 会话启动

    private fun startSession() {
        try {
            val filesDir = filesDir
            val prefix = TermuxBootstrap.installIfNeeded(filesDir) { msg ->
                runOnUiThread { statusView.text = msg }
            }
            val home = TermuxBootstrap.homeDir(filesDir).apply { mkdirs() }
            val bash = File(prefix, "bin/bash")
            if (!bash.isFile) throw RuntimeException("找不到 bash: ${bash.absolutePath}")

            val newSession = TerminalSession(
                bash.absolutePath,
                home.absolutePath,
                arrayOf("-bash"),
                buildEnv(prefix, home),
                8000,
                this
            )
            runOnUiThread { attachSession(newSession) }
        } catch (t: Throwable) {
            runOnUiThread { showFatal("启动终端失败", t) }
        }
    }

    private fun attachSession(newSession: TerminalSession) {
        session = newSession
        terminalView.attachSession(newSession)
        terminalView.requestFocus()
        statusView.text = "bash 已就绪 · 点屏幕呼出键盘"
        showKeyboard()
    }

    private fun restartSession() {
        statusView.text = "重启终端…"
        val old = session
        session = null
        Thread({
            runCatching { old?.finishIfRunning() }
            startSession()
        }, "ozj-restart").start()
    }

    private fun buildEnv(prefix: File, home: File): Array<String> {
        val p = prefix.absolutePath
        return arrayOf(
            "HOME=$home",
            "PREFIX=$p",
            "TMPDIR=$p/tmp",
            "PATH=$p/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=$p/lib",
            "TERMINFO=$p/share/terminfo",
            "SHELL=$p/bin/bash",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "EXTERNAL_STORAGE=/sdcard",
            "TERMUX_VERSION=0.118.0",
            "TERMUX_APP_PACKAGE=$packageName",
            "TERMUX__PREFIX=$p"
        )
    }

    // ------------------------------------------------------------ 输入辅助

    private fun sendBytes(bytes: ByteArray) {
        session?.write(bytes, 0, bytes.size)
        terminalView.requestFocus()
    }

    private fun sendText(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isNotEmpty()) sendText(text)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    // ------------------------------------------------------------ 错误界面

    private fun showFatal(title: String, t: Throwable) {
        Log.e(TAG, title, t)
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append(title).append("\n\n")
            append(sw.toString())
        }
        val tv = TextView(this).apply {
            setTextColor(0xFFFF8A80.toInt())
            setBackgroundColor(0xFF101010.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            this.text = text
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    // ------------------------------------------------- TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        val title = changedSession.title
        if (!title.isNullOrBlank()) statusView.text = title
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        statusView.text = "会话已结束（exit ${finishedSession.exitStatus}）· 点 ↻ 重开"
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        copyToClipboard(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        pasteFromClipboard()
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        Log.i(TAG, "shell pid = $pid")
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "stack", e)
    }

    // ---------------------------------------------------- TerminalViewClient

    override fun onScale(scale: Float): Float {
        val next = (fontSizePx * scale).toInt().coerceIn(dp(8), dp(30))
        if (next != fontSizePx) {
            fontSizePx = next
            terminalView.setTextSize(next)
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean =
        false

    override fun onEmulatorSet() = Unit

    companion object {
        private const val TAG = "OZJFLASH"
    }
}
