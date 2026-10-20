package www.xdyl.hygge.com

/**
 * 服务端时间格式不统一，统一收敛成 `yyyy-MM-dd HH:mm`。
 *
 * 实际见过的几种：
 *   2026-08-11 12:27:29         标准
 *   2026-09-02T06:05:39         ISO（带 T）
 *   2026-09-0206:05:39          少了分隔符（旧数据）
 *   2026-09-02T06:05:39.123Z    带毫秒 / 时区
 *
 * 之所以单独抽出来：论坛列表、帖子详情、回复区都用同一套格式，避免各处再写一遍。
 */
object TimeFmt {

    fun server(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        if (s.contains('T')) s = s.replace('T', ' ')
        s = s.substringBefore('.').substringBefore('Z').substringBefore('+').trim()
        // 形如 2026-09-0206:05:39 → 第 11 位插空格
        if (s.length >= 17 && s[10] != ' ') {
            s = s.substring(0, 10) + " " + s.substring(10)
        }
        // 去掉秒
        if (s.length > 16 && s[16] == ':') s = s.substring(0, 16)
        return s
    }
}