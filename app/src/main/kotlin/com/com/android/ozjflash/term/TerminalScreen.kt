package com.com.android.ozjflash.term

/**
 * 精简终端屏幕模型：维护 行×列 文本、光标位置，消费 PTY 输出的 ANSI/VT 序列。
 * 只保留文本与光标语义（颜色等 SGR 一律忽略），足够跑 bash/coreutils。
 */
class TerminalScreen(private val maxLines: Int = 1200) {

    private val lines = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    private var row = 0
    private var col = 0
    private var savedRow = 0
    private var savedCol = 0

    private var state = 0 // 0=ground 1=esc 2=csi 3=osc 4=esc-consume-one
    private val csi = StringBuilder()

    @Synchronized
    fun feed(text: String) {
        for (ch in text) {
            when (state) {
                0 -> ground(ch)
                1 -> esc(ch)
                2 -> csiChar(ch)
                3 -> oscChar(ch)
                else -> state = 0
            }
        }
    }

    @Synchronized
    fun snapshot(): String {
        val sb = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i]
            var end = line.length
            while (end > 0 && line[end - 1] == ' ') end--
            sb.append(line, 0, end)
            if (i != lines.size - 1) sb.append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        lines.add(StringBuilder())
        row = 0
        col = 0
    }

    // ---- 字符状态机 ----

    private fun ground(c: Char) {
        when (c) {
            '\u001b' -> state = 1
            '\r' -> col = 0
            '\n' -> newline()
            '\b' -> if (col > 0) col--
            '\t' -> col = (col / 8 + 1) * 8
            '\u0007' -> {}
            else -> if (c.code >= 32) put(c)
        }
    }

    private fun esc(c: Char) {
        when (c) {
            '[' -> { csi.clear(); state = 2 }
            ']' -> state = 3
            '7' -> { savedRow = row; savedCol = col; state = 0 }
            '8' -> { row = savedRow.coerceAtLeast(0); col = savedCol.coerceAtLeast(0); state = 0 }
            '(', ')', '*', '+', '#', '%' -> state = 4
            else -> state = 0
        }
    }

    private fun csiChar(c: Char) {
        if (c in '0'..'9' || c == ';' || c == '?' || c == '>' || c == ':' || c == ' ') {
            if (csi.length < 64) csi.append(c)
        } else {
            handleCsi(c)
            state = 0
        }
    }

    private fun oscChar(c: Char) {
        if (c == '\u0007') state = 0
        else if (c == '\u001b') state = 4 // ESC \ 结束
    }

    private fun handleCsi(f: Char) {
        val raw = csi.toString()
        val nums = raw.trimStart('?', '>', ':').split(';').map { it.trim().toIntOrNull() ?: 0 }
        fun n(i: Int, def: Int = 0) = nums.getOrElse(i) { def }
        when (f) {
            'A' -> row = (row - maxOf(1, n(0))).coerceAtLeast(0)
            'B' -> row = (row + maxOf(1, n(0))).coerceAtMost(lines.size)
            'C' -> col += maxOf(1, n(0))
            'D' -> col = (col - maxOf(1, n(0))).coerceAtLeast(0)
            'E' -> { row = (row + maxOf(1, n(0))).coerceAtMost(lines.size); col = 0 }
            'F' -> { row = (row - maxOf(1, n(0))).coerceAtLeast(0); col = 0 }
            'G' -> col = (maxOf(1, n(0)) - 1).coerceAtLeast(0)
            'd' -> { row = (maxOf(1, n(0)) - 1).coerceAtMost(lines.size); ensureRow() }
            'H', 'f' -> {
                row = (maxOf(1, n(0)) - 1).coerceAtLeast(0)
                col = (maxOf(1, n(1, 1)) - 1).coerceAtLeast(0)
                ensureRow()
            }
            'J' -> when (n(0)) {
                0 -> eraseBelow()
                1 -> eraseAbove()
                else -> clear()
            }
            'K' -> when (n(0)) {
                0 -> truncateLine(col)
                1 -> eraseBefore(col)
                else -> lines[cur()] = StringBuilder()
            }
            'P' -> deleteChars(maxOf(1, n(0)))
            'X' -> eraseChars(maxOf(1, n(0)))
            '@' -> insertChars(maxOf(1, n(0)))
            'L' -> insertLines(maxOf(1, n(0)))
            'M' -> deleteLines(maxOf(1, n(0)))
            'S' -> scrollUp(maxOf(1, n(0)))
            'T' -> scrollDown(maxOf(1, n(0)))
            else -> {} // m / h / l / r / t 等忽略
        }
    }

    // ---- 基础编辑 ----

    private fun cur(): Int = row.coerceIn(0, lines.size - 1)

    private fun ensureRow() {
        while (lines.size <= row) lines.add(StringBuilder())
        trim()
    }

    private fun newline() {
        row++
        col = 0
        ensureRow()
    }

    private fun put(c: Char) {
        val line = lines[cur()]
        while (line.length < col) line.append(' ')
        if (col < line.length) line.setCharAt(col, c) else line.append(c)
        col++
    }

    private fun trim() {
        while (lines.size > maxLines) {
            lines.removeAt(0)
            if (row > 0) row--
        }
    }

    private fun truncateLine(from: Int) {
        val line = lines[cur()]
        if (from < line.length) line.setLength(from.coerceAtLeast(0))
    }

    private fun eraseBefore(to: Int) {
        val line = lines[cur()]
        for (i in 0 until to.coerceAtMost(line.length)) line.setCharAt(i, ' ')
    }

    private fun eraseBelow() {
        truncateLine(col)
        while (lines.size > row + 1) lines.removeAt(lines.size - 1)
    }

    private fun eraseAbove() {
        for (i in 0 until cur()) lines[i] = StringBuilder()
        eraseBefore(col)
    }

    private fun deleteChars(count: Int) {
        val line = lines[cur()]
        if (col < line.length) {
            val end = (col + count).coerceAtMost(line.length)
            line.delete(col, end)
        }
    }

    private fun eraseChars(count: Int) {
        val line = lines[cur()]
        val end = (col + count).coerceAtMost(line.length)
        for (i in col until end) line.setCharAt(i, ' ')
    }

    private fun insertChars(count: Int) {
        val line = lines[cur()]
        while (line.length < col) line.append(' ')
        repeat(count) { line.insert(col, ' ') }
    }

    private fun insertLines(count: Int) {
        repeat(count) { lines.add(cur(), StringBuilder()) }
        trim()
    }

    private fun deleteLines(count: Int) {
        repeat(count) {
            if (lines.size > 1 && cur() < lines.size) lines.removeAt(cur())
        }
    }

    private fun scrollUp(count: Int) {
        repeat(count) {
            lines.removeAt(0)
            lines.add(StringBuilder())
        }
        row = (row - count).coerceAtLeast(0)
    }

    private fun scrollDown(count: Int) {
        repeat(count) {
            lines.add(0, StringBuilder())
            lines.removeAt(lines.size - 1)
        }
        row = (row + count).coerceAtMost(lines.size - 1)
    }
}
