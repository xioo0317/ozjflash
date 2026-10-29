package com.com.android.ozjflash

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.com.android.ozjflash.term.Bootstrap
import com.com.android.ozjflash.term.TerminalSession
import com.com.android.ozjflash.ui.theme.ComposeEmptyActivityTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                TerminalApp()
            }
        }
    }
}

private val BgColor = Color(0xFF101010)
private val FgColor = Color(0xFFD8D8D8)
private val HintColor = Color(0xFF8A8A8A)
private val AccentColor = Color(0xFF64B5F6)

@Composable
fun TerminalApp() {
    val ctx = LocalContext.current
    var installed by remember { mutableStateOf(Bootstrap.isInstalled(ctx)) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf(if (installed) "就绪" else "首次启动：解压 Termux 环境(约29MB)") }
    var version by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var rows by remember { mutableIntStateOf(40) }
    var cols by remember { mutableIntStateOf(100) }

    val session = remember { TerminalSession(ctx) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val lastTick = remember { longArrayOf(0L) }
    val scroll = rememberScrollState()

    fun startSession() {
        session.onUpdate = {
            val now = SystemClock.uptimeMillis()
            if (now - lastTick[0] > 80) {
                lastTick[0] = now
                handler.post { version++ }
            }
        }
        session.onExit = { msg -> handler.post { status = msg; version++ } }
        val ok = session.start(rows, cols)
        status = if (ok) "已连接 bash · ${cols}x$rows" else "启动失败"
        version++
    }

    fun doSend(text: String) {
        if (text.isEmpty()) return
        if (!session.running) {
            status = "会话未运行"
            return
        }
        session.write(text + "\n")
    }

    LaunchedEffect(Unit) {
        if (!installed) {
            installing = true
            withContext(Dispatchers.IO) {
                try {
                    Bootstrap.install(ctx) { done, total ->
                        progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                    }
                } catch (t: Throwable) {
                    handler.post { status = "解压失败: ${t.message}" }
                }
            }
            installing = false
            installed = Bootstrap.isInstalled(ctx)
            status = if (installed) "环境就绪" else "环境解压失败"
        }
        if (installed && !session.running) startSession()
    }

    LaunchedEffect(version) {
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgColor)
            .imePadding()
    ) {
        // 顶部状态栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = status,
                color = HintColor,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (installed) {
                TextButton(onClick = {
                    session.stop()
                    status = "重启中…"
                    handler.postDelayed({ startSession() }, 400)
                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("重启", color = AccentColor, fontSize = 12.sp)
                }
            }
        }
        if (installing) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }

        // 终端输出区
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val density = LocalDensity.current
            val charW = with(density) { 12.sp.toPx() } * 0.62f
            val lineH = with(density) { 15.sp.toPx() }
            val c = (constraints.maxWidth / charW).toInt().coerceIn(20, 400)
            val r = (constraints.maxHeight / lineH).toInt().coerceIn(5, 300)
            LaunchedEffect(c, r) {
                if (c != cols || r != rows) {
                    cols = c
                    rows = r
                    session.resize(r, c)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = remember(version) { session.screen.snapshot() },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = FgColor
                )
            }
        }

        // 功能键
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyBtn("ESC") { session.write("\u001b") }
            KeyBtn("TAB") { session.write("\t") }
            KeyBtn("^C") { session.write("\u0003") }
            KeyBtn("^D") { session.write("\u0004") }
            KeyBtn("^Z") { session.write("\u001a") }
            KeyBtn("^L") { session.write("\u000c") }
            KeyBtn("↑") { session.write("\u001b[A") }
            KeyBtn("↓") { session.write("\u001b[B") }
            KeyBtn("→") { session.write("\u001b[C") }
            KeyBtn("←") { session.write("\u001b[D") }
            KeyBtn("清屏") { doSend("clear") }
            KeyBtn("粘贴") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                input += t
            }
        }

        // 输入行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF1C1C1C), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                textStyle = TextStyle(
                    color = Color(0xFFE8E8E8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(AccentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    doSend(input)
                    input = ""
                })
            )
            Button(onClick = {
                doSend(input)
                input = ""
            }) {
                Text("发送", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun KeyBtn(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFFB0BEC5), fontFamily = FontFamily.Monospace)
    }
}
