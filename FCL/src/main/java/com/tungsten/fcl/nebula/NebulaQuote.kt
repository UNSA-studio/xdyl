package com.tungsten.fcl.nebula

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * 每日名言：六个分类（警世箴言 / 理性思辨 / 心灵疗愈 / 存在哲思 / 人际纽带 / 行动召唤），
 * 每天固定一条，跨天自动更换，缓存在本地偏好中。
 */
object NebulaQuote {

    data class Quote(
        val chinese: String,
        val english: String,
        val author: String,
        val authorEn: String,
        val source: String,
        val sourceEn: String
    )

    private val categories = listOf("WH", "RW", "HC", "ED", "CE", "AC")
    private val categoryNames = mapOf(
        "WH" to "警世箴言",
        "RW" to "理性思辨",
        "HC" to "心灵疗愈",
        "ED" to "存在哲思",
        "CE" to "人际纽带",
        "AC" to "行动召唤"
    )

    fun nameOf(category: String): String = categoryNames[category] ?: category

    /** 加载今日名言；失败返回 null。必须在 IO 线程调用。 */
    fun load(context: Context): Pair<String, Quote>? {
        val prefs = context.getSharedPreferences("nebula_quote", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val last = prefs.getString("quote_date", "")

        if (last == today) {
            val cat = prefs.getString("quote_cat", null)
            val idx = prefs.getInt("quote_index", -1)
            if (cat != null && idx >= 0) {
                readQuote(context, cat, idx)?.let { return Pair(cat, it) }
            }
        }

        val all = mutableListOf<Triple<String, Int, Quote>>()
        for (cat in categories) {
            try {
                val text = context.assets.open("$cat.json").bufferedReader().readText()
                val arr = JSONObject(text).getJSONArray("quotes")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    all.add(
                        Triple(
                            cat, i,
                            Quote(
                                o.optString("chinese"),
                                o.optString("english"),
                                o.optString("author"),
                                o.optString("author_en"),
                                o.optString("source"),
                                o.optString("source_en")
                            )
                        )
                    )
                }
            } catch (e: Throwable) {
                // 单个分类读取失败不影响其他分类
            }
        }
        if (all.isEmpty()) return null
        val pick = all[Random().nextInt(all.size)]
        prefs.edit()
            .putString("quote_date", today)
            .putString("quote_cat", pick.first)
            .putInt("quote_index", pick.second)
            .apply()
        return Pair(pick.first, pick.third)
    }

    private fun readQuote(context: Context, cat: String, index: Int): Quote? {
        return try {
            val text = context.assets.open("$cat.json").bufferedReader().readText()
            val arr = JSONObject(text).getJSONArray("quotes")
            if (index !in 0 until arr.length()) return null
            val o = arr.getJSONObject(index)
            Quote(
                o.optString("chinese"),
                o.optString("english"),
                o.optString("author"),
                o.optString("author_en"),
                o.optString("source"),
                o.optString("source_en")
            )
        } catch (e: Throwable) {
            null
        }
    }
}