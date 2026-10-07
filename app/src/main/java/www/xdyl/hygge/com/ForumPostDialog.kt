package www.xdyl.hygge.com

import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 论坛帖子详情（我们的风格）：
 * 正文（含 Markdown 图片渲染）/ 点赞 / 回复列表 / 回复输入。
 */
object ForumPostDialog {

    private val IMAGE_REGEX = Regex("!\\[.*?]\\((.*?)\\)")

    fun show(activity: MainActivity, api: ApiClient, postId: Int) {
        val scroll = ScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(activity, 20), dip(activity, 16), dip(activity, 20), dip(activity, 16))
        }
        scroll.addView(col)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()

        fun reload() {
            activity.lifecycleScope.launch {
                col.removeAllViews()
                val loading = TextView(activity).apply {
                    text = "加载中…"
                    setTextColor(0xFF9AA0A6.toInt())
                    textSize = 13f
                }
                col.addView(loading)
                try {
                    val root = withContext(Dispatchers.IO) {
                        api.get("/forum/post/$postId", requiresAuth = false)
                    }
                    val data = root.optJSONObject("data")
                    val post = data?.optJSONObject("post")
                    val replies = data?.optJSONArray("replies")
                    col.removeAllViews()
                    if (post == null) {
                        col.addView(simpleText(activity, "帖子不存在或已被删除", 14f, 0xFFE57373.toInt()))
                        return@launch
                    }

                    // 标题
                    col.addView(simpleText(activity, post.optString("title", "帖子"), 18f, 0xFFA0C4FF.toInt(), bold = true))
                    // 作者行
                    val meta = buildString {
                        append(post.optString("nickname", post.optString("username", "匿名")))
                        val t = post.optString("created_at", "")
                        if (t.isNotBlank()) append(" · ").append(t)
                        append(" · 浏览 ").append(post.optInt("views", 0))
                        val ut = post.optString("user_title", "")
                        if (ut.isNotBlank()) append(" · ").append(ut)
                    }
                    col.addView(simpleText(activity, meta, 12f, 0xFF9AA0A6.toInt(), topMargin = 6))

                    // 正文（剥离 Markdown 图片语法后显示文字）
                    val content = post.optString("content", "")
                    val textOnly = content.replace(IMAGE_REGEX, "").trim()
                    if (textOnly.isNotBlank()) {
                        col.addView(simpleText(activity, textOnly, 14f, 0xFFEDEDED.toInt(), topMargin = 10))
                    }
                    // 正文中的图片
                    IMAGE_REGEX.findAll(content).forEach { m ->
                        addImage(activity, col, m.groupValues[1])
                    }

                    // 点赞行
                    val likeRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dip(activity, 12), 0, 0)
                    }
                    val likeBtn = MaterialButton(activity).apply {
                        text = "赞 ${post.optInt("likes", 0)}"
                        textSize = 13f
                        setTextColor(0xFF10131A.toInt())
                        setBackgroundColor(0xFFA0C4FF.toInt())
                        minWidth = 0
                        setPadding(dip(activity, 18), 0, dip(activity, 18), 0)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dip(activity, 38)
                        )
                        setOnClickListener {
                            activity.lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        api.post("/forum/post/$postId/like", JSONObject())
                                    }
                                    Toast.makeText(activity, "已点赞", Toast.LENGTH_SHORT).show()
                                    reload()
                                } catch (e: Exception) {
                                    Toast.makeText(activity, "点赞失败：" + e.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    likeRow.addView(likeBtn)
                    col.addView(likeRow)

                    // 回复区
                    col.addView(simpleText(activity, "回复（${replies?.length() ?: 0}）", 14f, 0xFFA0C4FF.toInt(), topMargin = 18, bold = true))
                    if (replies != null) {
                        for (i in 0 until replies.length()) {
                            val r = replies.optJSONObject(i) ?: continue
                            val head = buildString {
                                append(r.optString("nickname", r.optString("username", "匿名")))
                                val t = r.optString("created_at", "")
                                if (t.isNotBlank()) append(" · ").append(t)
                            }
                            col.addView(simpleText(activity, head, 12f, 0xFF9AA0A6.toInt(), topMargin = 12))
                            val rc = r.optString("content", "")
                            val rText = rc.replace(IMAGE_REGEX, "").trim()
                            if (rText.isNotBlank()) {
                                col.addView(simpleText(activity, rText, 14f, 0xFFEDEDED.toInt(), topMargin = 4))
                            }
                            // 回复里的图片（表情 / 上传图）同样渲染
                            IMAGE_REGEX.findAll(rc).take(2).forEach { m ->
                                addImage(activity, col, m.groupValues[1])
                            }
                        }
                    }

                    // 回复输入
                    val input = EditText(activity).apply {
                        hint = "写下你的回复…"
                        setTextColor(0xFFEDEDED.toInt())
                        setHintTextColor(0xFF6B6B6B.toInt())
                        textSize = 14f
                        setBackgroundColor(0xFF1E1E1E.toInt())
                        setPadding(dip(activity, 12), dip(activity, 10), dip(activity, 12), dip(activity, 10))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dip(activity, 14) }
                    }
                    col.addView(input)
                    val sendBtn = MaterialButton(activity).apply {
                        text = "发送回复"
                        textSize = 13f
                        setTextColor(0xFF10131A.toInt())
                        setBackgroundColor(0xFFA0C4FF.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dip(activity, 42)
                        ).apply { topMargin = dip(activity, 10) }
                        setOnClickListener {
                            val text = input.text.toString().trim()
                            if (text.isEmpty()) {
                                Toast.makeText(activity, "回复内容不能为空", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            activity.lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        api.post("/forum/post/$postId/reply", JSONObject().put("content", text))
                                    }
                                    Toast.makeText(activity, "回复成功", Toast.LENGTH_SHORT).show()
                                    reload()
                                } catch (e: Exception) {
                                    Toast.makeText(activity, "回复失败：" + e.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    col.addView(sendBtn)
                } catch (e: Exception) {
                    col.removeAllViews()
                    col.addView(simpleText(activity, "加载失败：" + e.message, 14f, 0xFFE57373.toInt()))
                }
            }
        }

        reload()
        dialog.show()
    }

    private fun addImage(activity: MainActivity, col: LinearLayout, url: String) {
        val iv = ImageView(activity).apply {
            adjustViewBounds = true
            maxHeight = dip(activity, 360)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dip(activity, 10) }
            // 圆角占位背景
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f
                setColor(0xFF1E1E1E.toInt())
            }
            clipToOutline = true
        }
        col.addView(iv)
        SimpleImageLoader.load(activity, url, iv)
    }

    private fun simpleText(
        activity: MainActivity,
        text: String,
        size: Float,
        color: Int,
        topMargin: Int = 0,
        bold: Boolean = false
    ): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        movementMethod = LinkMovementMethod.getInstance()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dip(activity, topMargin) }
    }

    private fun dip(activity: MainActivity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}