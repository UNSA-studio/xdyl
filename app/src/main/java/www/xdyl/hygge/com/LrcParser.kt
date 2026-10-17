package www.xdyl.hygge.com

import java.io.File

/**
 * LRC 歌词解析器
 *
 * 支持：
 *  - 标准时间标签：[mm:ss.xx] / [mm:ss.xxx] / [mm:ss]（也兼容 [mm:ss:xx]）
 *  - 一行多个时间标签：[00:10.00][00:30.00]同一句
 *  - 元数据标签：[ti:] [ar:] [al:] [by:] [offset:±ms]
 *  - 双语歌词：
 *      · 同一时间戳出现两行 → 第一行原文，第二行译文
 *      · 单行内用 " / " 或 " // " 分隔 → 前半原文，后半译文
 *
 * 输出按时间升序，并已应用 offset 偏移。
 */
object LrcParser {

    data class Line(
        val timeMs: Long,
        val text: String,
        val translation: String? = null
    )

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val META_TAG = Regex("^\\[([a-zA-Z#]+):(.*)]$")

    /** 解析 LRC 文本 */
    fun parse(raw: String): List<Line> {
        if (raw.isBlank()) return emptyList()

        var offsetMs = 0L
        // 时间 -> 该时间下的文本行（保持出现顺序）
        val buckets = LinkedHashMap<Long, MutableList<String>>()

        raw.lineSequence().forEach { origin ->
            val line = origin.trim()
            if (line.isEmpty()) return@forEach

            // 元数据
            val meta = META_TAG.find(line)
            if (meta != null && !TIME_TAG.containsMatchIn(line)) {
                if (meta.groupValues[1].equals("offset", true)) {
                    offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }

            // 收集本行所有时间标签
            val times = TIME_TAG.findAll(line).mapNotNull { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val fracRaw = m.groupValues[3]
                var ms = 0L
                if (fracRaw.isNotEmpty()) {
                    ms = when (fracRaw.length) {
                        1 -> fracRaw.toLong() * 100
                        2 -> fracRaw.toLong() * 10
                        else -> fracRaw.take(3).toLong()
                    }
                }
                min * 60_000 + sec * 1_000 + ms
            }.toList()

            if (times.isEmpty()) return@forEach

            // 去掉时间标签后的正文
            val body = TIME_TAG.replace(line, "").trim()
            if (body.isEmpty()) return@forEach

            times.forEach { t ->
                buckets.getOrPut(t) { mutableListOf() }.add(body)
            }
        }

        val result = ArrayList<Line>(buckets.size)
        for ((time, texts) in buckets) {
            val adjusted = (time + offsetMs).coerceAtLeast(0)
            val first = texts.first()
            if (texts.size >= 2) {
                // 同一时间戳两行 = 原文 + 译文
                result.add(Line(adjusted, first, texts[1]))
            } else {
                // 单行内可能用 " / " 分隔原文与译文
                val split = splitInlineBilingual(first)
                result.add(Line(adjusted, split.first, split.second))
            }
        }
        result.sortBy { it.timeMs }
        return result
    }

    /** 单行内的双语分隔：「原文 / 译文」或「原文 // 译文」 */
    private fun splitInlineBilingual(text: String): Pair<String, String?> {
        for (sep in listOf(" // ", "//", " / ")) {
            val idx = text.indexOf(sep)
            if (idx > 0 && idx < text.length - sep.length) {
                val a = text.substring(0, idx).trim()
                val b = text.substring(idx + sep.length).trim()
                if (a.isNotEmpty() && b.isNotEmpty()) return a to b
            }
        }
        return text to null
    }

    /** 尝试从音频文件所在目录找同名 .lrc（也接受同名 .LRC） */
    fun findSidecar(audio: File): File? {
        val dir = audio.parentFile ?: return null
        val base = audio.name.substringBeforeLast('.')
        val candidates = listOf(
            File(dir, "$base.lrc"),
            File(dir, "$base.LRC"),
            File(dir, "$base.Lrc")
        )
        return candidates.firstOrNull { it.isFile }
    }

    /** 读取并解析文件（失败返回空列表） */
    fun parseFile(file: File): List<Line> = try {
        parse(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        try {
            parse(file.readText(java.nio.charset.Charset.forName("GBK")))
        } catch (e2: Exception) {
            emptyList()
        }
    }
}