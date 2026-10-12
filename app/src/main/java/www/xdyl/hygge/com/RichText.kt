package www.xdyl.hygge.com

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.widget.TextView

/**
 * 渲染服务端富文本标记：
 *
 *  1. 颜色：`&#RRGGBB`（持续到下一个标记）—— 星灯云浪自定义语法
 *  2. 颜色标签：`<color=#RRGGBB>...</color>`
 *  3. 加粗：`**文字**`
 *  4. 删除线：`~~文字~~`
 */
object RichText {

    private fun isHex(s: String): Boolean =
        s.length == 6 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun parseColor(hex: String): Int? = try {
        (0xFF000000L or hex.toLong(16)).toInt()
    } catch (e: Exception) {
        null
    }

    fun apply(tv: TextView, raw: String) {
        if (raw.isEmpty()) {
            tv.text = ""
            return
        }
        val defaultColor = tv.currentTextColor
        // 没有标记时直接用默认渲染（快路径）
        if (!raw.contains("&#") && !raw.contains("<color=") && !raw.contains("**") && !raw.contains("~~")) {
            tv.text = raw
            return
        }

        val sb = SpannableStringBuilder()
        var color = defaultColor
        var bold = false
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

            val start = sb.length
            sb.append(raw[i])
            if (color != defaultColor) {
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        if (!raw.contains("&#") && !raw.contains("<color=") && !raw.contains("**") && !raw.contains("~~")) return raw
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
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }
}