package www.xdyl.hygge.com

/**
 * 文本解码：把服务端返回的 HTML 实体 / <br> 还原成纯文本。
 *
 * 之前全项目一处都没解，导致 &amp;、&#39;、<br> 原样显示；
 * 而某些地方（内嵌网页）又会解析 —— 同一个字符在不同页面表现不一致。
 * 现在所有服务端文本统一走这里。
 */
object HtmlText {

    /** '&' 用 unicode 转义，避开构建工具对 XML 实体的提前解码 */
    private const val AMP = "\u0026"

    private val BR = Regex("(?i)<br\\s*/?>")
    private val PARA = Regex("(?i)</?p\\s*>")
    private val NUM_ENT = Regex("#(x?)([0-9a-fA-F]+);")

    fun plain(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var t = raw
        t = BR.replace(t, "\n")
        t = PARA.replace(t, "\n")
        t = t.replace(AMP + "nbsp;", " ")
            .replace(AMP + "lt;", "<")
            .replace(AMP + "gt;", ">")
            .replace(AMP + "quot;", "\"")
            .replace(AMP + "apos;", "'")
            .replace(AMP + "#39;", "'")
        // 数字实体：&#123; / &#x1F600;
        t = Regex(AMP + NUM_ENT.pattern).replace(t) { m ->
            try {
                val code = if (m.groupValues[1].isNotEmpty()) {
                    m.groupValues[2].toInt(16)
                } else {
                    m.groupValues[2].toInt()
                }
                String(Character.toChars(code))
            } catch (e: Exception) {
                m.value
            }
        }
        // &amp; 必须最后处理，避免 &amp;lt; 被二次解码
        t = t.replace(AMP + "amp;", AMP)
        t = Regex("\n{3,}").replace(t, "\n\n")
        return t.trim()
    }
}
