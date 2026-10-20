package www.xdyl.hygge.com

import org.json.JSONObject

/**
 * 玩家显示名：昵称 + 称号。
 *
 * 重要：称号里常带 Minecraft 颜色码（如 `&3俯瞰&9蓝天`），
 * 这里**不能剥掉**，否则颜色就没了；交给 RichText 去渲染。
 * 但在称号末尾补一个 `&r`，避免颜色“污染”同一行后面的文字（时间 / 浏览数）。
 *
 * 需要纯文本的场合（例如直接 setText 不看 RichText 的地方）用 [plainName]。
 */
object Display {

    fun name(obj: JSONObject?, fallback: String = "匿名"): String {
        if (obj == null) return fallback
        val n = obj.optString("nickname", "")
            .ifBlank { obj.optString("player_name", "") }
            .ifBlank { obj.optString("username", "") }
            .ifBlank { fallback }
        // 只做 HTML 实体解码，保留 &0-&f 这类颜色码
        val t = HtmlText.plain(obj.optString("user_title", "")).trim()
        return if (t.isBlank()) n else n + "「" + t + "&r」"
    }

    /** 纯文本版（去掉颜色/格式码），用于日志、直接 setText 的场景 */
    fun plainName(obj: JSONObject?, fallback: String = "匿名"): String =
        RichText.strip(name(obj, fallback))
}
