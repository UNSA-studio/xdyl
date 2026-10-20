package www.xdyl.hygge.com

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.TextView

/**
 * 渲染服务端富文本标记：
 *
 *1. 颜色：`&#RRGGBB`（星灯云浪自定义语法，持续到下一个标记）
 *2. 颜色标签：`<color=#RRGGBB>...</color>`
 *3. **Minecraft 单字符颜色码**：`&0`~`&9`、`&a`~`&f`（称号、聊天里最常见）
 *4. 格式：`**加粗**`、`~~删除线~~`，以及 MC 的 `&l`/`&o`/`&n`/`&m`/`&r`/`&k`
 *
 * 之前只支持 &#RRGGBB 和 <color>，带 &3 这类代码的文本会原样显示。
 */
object RichText {

    /** Minecraft 经典16色 */
    private val MC: Map<Char, Int> = mapOf(
        '0' to 0xFF000000.toInt(), '1' to 0xFF0000AA.toInt(),
        '2' to 0xFF00AA00.toInt(), '3' to 0xFF00AAAA.toInt(),
        '4' to 0xFFAA0000.toInt(), '5' to 0xFFAA00AA.toInt(),
        '6' to 0xFFFFAA00.toInt(), '7' to 0xFFAAAAAA.toInt(),
        '8' to 0xFF555555.toInt(), '9' to 0xFF5555FF.toInt(),
        'a' to 0xFF55FF55.toInt(), 'b' to 0xFF55FFFF.toInt(),
        'c' to 0xFFFF5555.toInt(), 'd' to 0xFFFF55FF.toInt(),
        'e' to 0xFFFFFF55.toInt(), 'f' to 0xFFFFFFFF.toInt()
    )

    private fun isHex(s: String): Boolean =
        s.length == 6 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun parseColor(hex: String): Int? = try {
        (0xFF000000L or hex.toLong(16)).toInt()
    } catch (e: Exception) {
        null
    }

    /** 文本里是否含有任何可解析的标记 */
    private fun hasMarkup(raw: String): Boolean {
        if (raw.contains("&#") || raw.contains("<color=") || raw.contains("**") || raw.contains("~~")) return true
        // MC 代码
        var i = 0
        while (i < raw.length - 1) {
            if (raw[i] == '&') {
                val c = raw[i + 1].lowercaseChar()
                if (MC.containsKey(c) || c in "klmnor") return true
            }
            i++
        }
        return false
    }

    /**
     * 解析成带样式的 CharSequence（不直接写进 TextView）。
     * 供拼接场景使用：比如「名字 + 带颜色的称号」。
     */
    fun toSpanned(raw: String, defaultColor: Int): CharSequence {
        if (raw.isEmpty()) return ""
        if (!hasMarkup(raw)) return raw

        val sb = SpannableStringBuilder()
        var color = defaultColor
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var i = 0

        while (i < raw.length) {
            if (raw.startsWith("&#", i) && i + 9 <= raw.length) {
                val hex = raw.substring(i + 2, i + 8)
                if (isHex(hex)) {
                    parseColor(hex)?.let { color = it }
                    i += 8
                    continue
                }
            }
            if (raw.startsWith("<color=#", i)) {
                val end = raw.indexOf('>', i)
                if (end > i) {
                    val hex = raw.substring(i + 8, end).trim().removePrefix("#")
                    if (isHex(hex)) {
                        parseColor(hex)?.let { color = it }
                        i = end + 1
                        continue
                    }
                }
            }
            if (raw.startsWith("</color>", i)) {
                color = defaultColor
                i += 8
                continue
            }
            if (raw.startsWith("**", i)) {
                bold = !bold
                i += 2
                continue
            }
            if (raw.startsWith("~~", i)) {
                strike = !strike
                i += 2
                continue
            }
            if (raw[i] == '&' && i + 1 < raw.length) {
                val c = raw[i + 1].lowercaseChar()
                val mcColor = MC[c]
                if (mcColor != null) {
                    color = mcColor
                    i += 2
                    continue
                }
                when (c) {
                    'l' -> { bold = true; i += 2; continue }
                    'o' -> { italic = true; i += 2; continue }
                    'n' -> { underline = true; i += 2; continue }
                    'm' -> { strike = true; i += 2; continue }
                    'k' -> { i += 2; continue }
                    'r' -> {
                        color = defaultColor
                        bold = false; italic = false; underline = false; strike = false
                        i += 2
                        continue
                    }
                }
            }

            val start = sb.length
            sb.append(raw[i])
            if (color != defaultColor) {
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (italic) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (underline) {
                sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (strike) {
                sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i++
        }
        return sb
    }

    fun apply(tv: TextView, raw: String) {
        if (raw.isEmpty()) {
            tv.text = ""
            return
        }
        val defaultColor = tv.currentTextColor
        if (!hasMarkup(raw)) {
            tv.text = raw
            return
        }

        val sb = SpannableStringBuilder()
        var color = defaultColor
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var i = 0

        while (i < raw.length) {
            // &#RRGGBB
            if (raw.startsWith("&#", i) && i + 9 <= raw.length) {
                val hex = raw.substring(i + 2, i + 8)
                if (isHex(hex)) {
                    parseColor(hex)?.let { color = it }
                    i += 8
                    continue
                }
            }
            // <color=#RRGGBB>
            if (raw.startsWith("<color=#", i)) {
                val end = raw.indexOf('>', i)
                if (end > i) {
                    val hex = raw.substring(i + 8, end).trim().removePrefix("#")
                    if (isHex(hex)) {
                        parseColor(hex)?.let { color = it }
                        i = end + 1
                        continue
                    }
                }
            }
            // </color>
            if (raw.startsWith("</color>", i)) {
                color = defaultColor
                i += 8
                continue
            }
            // **加粗**
            if (raw.startsWith("**", i)) {
                bold = !bold
                i += 2
                continue
            }
            // ~~删除线~~
            if (raw.startsWith("~~", i)) {
                strike = !strike
                i += 2
                continue
            }
            // Minecraft &x 代码
            if (raw[i] == '&' && i + 1 < raw.length) {
                val c = raw[i + 1].lowercaseChar()
                val mcColor = MC[c]
                if (mcColor != null) {
                    color = mcColor
                    i += 2
                    continue
                }
                when (c) {
                    'l' -> { bold = true; i += 2; continue }
                    'o' -> { italic = true; i += 2; continue }
                    'n' -> { underline = true; i += 2; continue }
                    'm' -> { strike = true; i += 2; continue }
                    'k' -> { i += 2; continue }              // 乱码效果：直接丢弃
                    'r' -> {
                        color = defaultColor
                        bold = false; italic = false; underline = false; strike = false
                        i += 2
                        continue
                    }
                }
            }

            val start = sb.length
            sb.append(raw[i])
            if (color != defaultColor) {
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (italic) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (underline) {
                sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (strike) {
                sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i++
        }
        tv.text = sb
    }

    /** 去掉所有标记，返回纯文本 */
    fun strip(raw: String): String {
        if (raw.isEmpty()) return raw
        if (!hasMarkup(raw)) return raw
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("&#", i) && i + 9 <= raw.length) {
                val hex = raw.substring(i + 2, i + 8)
                if (isHex(hex)) {
                    i += 8
                    continue
                }
            }
            if (raw.startsWith("<color=#", i)) {
                val end = raw.indexOf('>', i)
                if (end > i && isHex(raw.substring(i + 8, end).trim().removePrefix("#"))) {
                    i = end + 1
                    continue
                }
            }
            if (raw.startsWith("</color>", i)) {
                i += 8
                continue
            }
            if (raw.startsWith("**", i) || raw.startsWith("~~", i)) {
                i += 2
                continue
            }
            if (raw[i] == '&' && i + 1 < raw.length) {
                val c = raw[i + 1].lowercaseChar()
                if (MC.containsKey(c) || c in "klmnor") {
                    i += 2
                    continue
                }
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }
}
