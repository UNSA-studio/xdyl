package www.xdyl.hygge.com

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView

/**
 * 渲染服务端富文本颜色标记。
 *
 * 服务端格式：`&#RRGGBB` 后跟文字（颜色持续到下一个标记或文本结尾），例如
 * `&#FF0000剪&#FF0033越&#FF0066星` → 每个字不同颜色。
 */
object RichText {

    fun apply(tv: TextView, raw: String) {
        if (raw.isEmpty() || !raw.contains("&#")) {
            tv.text = raw
            return
        }
        val defaultColor = tv.currentTextColor
        val sb = SpannableStringBuilder()
        var color = defaultColor
        var i = 0
        while (i < raw.length) {
            // 尝试解析 &#RRGGBB
            if (raw.startsWith("&#", i) && i + 9 <= raw.length) {
                val hex = raw.substring(i + 2, i + 8)
                if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    color = try {
                        (0xFF000000L or hex.toLong(16)).toInt()
                    } catch (e: Exception) {
                        color
                    }
                    i += 8
                    continue
                }
            }
            val start = sb.length
            sb.append(raw[i])
            sb.setSpan(
                ForegroundColorSpan(color), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            i++
        }
        tv.text = sb
    }

    /** 去掉颜色标记，返回纯文本（用于列表摘要等不需要着色的场景） */
    fun strip(raw: String): String {
        if (!raw.contains("&#")) return raw
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("&#", i) && i + 9 <= raw.length) {
                val hex = raw.substring(i + 2, i + 8)
                if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    i += 8
                    continue
                }
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }
}