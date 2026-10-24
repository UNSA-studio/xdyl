package www.xdyl.hygge.com

import java.io.File

/**
 * 轻量歌词：只做「时间 → 文本」映射，用于首页「每日歌曲」的单行歌词显示。
 *
 * 支持两种格式：
 *   1) 标准 LRC：      [00:12.34]正文
 *   2) 自定义格式：    [00:12.34]正文[00:15.00]   ← 行尾结束时间会被忽略
 */
object SongLyric {

    private val TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})[.:](\\d{1,3})\\]")

    fun load(file: File): List<Pair<Int, String>> {
        if (!file.exists()) return emptyList()
        // 同一时间戳可能有双语两行（原文 + 译文），先按时间戳聚合
        val buckets = LinkedHashMap<Int, MutableList<String>>()
        try {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val tags = TIME.findAll(line).toList()
                if (tags.isEmpty()) return@forEach
                val g = tags.first().groupValues
                val mm = g[1].toIntOrNull() ?: return@forEach
                val ss = g[2].toIntOrNull() ?: return@forEach
                val frac = g[3]
                val cs = frac.toIntOrNull() ?: 0
                val ms = mm * 60000 + ss * 1000 + if (frac.length >= 3) cs else cs * 10
                val text = TIME.replace(line, "").trim()
                if (text.isEmpty()) return@forEach
                val list = buckets[ms]
                if (list == null) {
                    buckets[ms] = mutableListOf(text)
                } else if (!list.contains(text)) {
                    list.add(text)
                }
            }
        } catch (_: Exception) {
        }
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val out = buckets.map { (ms, texts) ->
            // 双语：中文放第一行，其余（原文）放第二行
            val cn = texts.filter { cjk.containsMatchIn(it) }
            val other = texts.filterNot { cjk.containsMatchIn(it) }
            val merged = when {
                cn.isEmpty() || other.isEmpty() -> texts.joinToString("\n")
                else -> (cn + other).joinToString("\n")
            }
            ms to merged
        }
        return out.sortedBy { it.first }
    }

    /** 取当前播放位置对应的那一行 */
    fun lineAt(lines: List<Pair<Int, String>>, posMs: Int): String {
        val i = indexAt(lines, posMs)
        return if (i in lines.indices) lines[i].second else ""
    }

    /** 取当前播放位置对应的行号（卡拉OK滚动用） */
    fun indexAt(lines: List<Pair<Int, String>>, posMs: Int): Int {
        if (lines.isEmpty()) return -1
        var r = 0
        for (i in lines.indices) {
            if (lines[i].first <= posMs) r = i else break
        }
        return r
    }

    /** 读取 lrc 头部标签，例如 tag(f, "ar") 取歌手 */
    fun tag(file: File, key: String): String {
        if (!file.exists()) return ""
        return try {
            file.readLines(Charsets.UTF_8).forEach { l ->
                val t = l.trim()
                if (t.startsWith("[" + key + ":")) {
                    return t.substringAfter(":").substringBefore("]").trim()
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}