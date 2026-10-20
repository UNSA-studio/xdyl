package www.xdyl.hygge.com

import org.json.JSONObject

/**
 * 玩家显示名：昵称 + 称号。
 *
 * 服务端每条帖子 / 回复 / 榜单数据都带 user_title（玩家自定义称号），
 * 但之前只有帖子详情一处显示，列表、回复、榜单全丢了。
 * 现在统一走这里。
 */
object Display {

    fun name(obj: JSONObject?, fallback: String = "匿名"): String {
        if (obj == null) return fallback
        val n = obj.optString("nickname", "")
            .ifBlank { obj.optString("player_name", "") }
            .ifBlank { obj.optString("username", "") }
            .ifBlank { fallback }
        val t = RichText.strip(HtmlText.plain(obj.optString("user_title", ""))).trim()
        return if (t.isBlank()) n else n + "「" + t + "」"
    }
}
