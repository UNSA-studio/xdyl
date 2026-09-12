package com.tungsten.fcl.nebula

import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.NebulaMainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 社区页：来自星灯云浪的公告与论坛帖子（匿名可读）。
 */
class NebulaCommunityPage(
    private val activity: NebulaMainActivity,
    root: View
) {
    private val container: LinearLayout = root.findViewById(R.id.communityContainer)
    private val tvHint: TextView = root.findViewById(R.id.tvCommunityHint)
    private val btnRefresh: View = root.findViewById(R.id.btnRefreshCommunity)

    private val api by lazy { ApiClient(SessionStore(activity)) }
    private var loaded = false
    private var loading = false

    init {
        btnRefresh.setOnClickListener { onShow(force = true) }
    }

    fun onShow(force: Boolean = false) {
        if (loading) return
        if (loaded && !force) return
        load()
    }

    private fun load() {
        loading = true
        activity.lifecycleScope.launch {
            try {
                val announcements = withContext(Dispatchers.IO) {
                    try {
                        val root = api.get("/announcements", requiresAuth = false)
                        val arr = api.extractList(root)
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val title = api.firstString(o, "title", "name", "subject") ?: "公告"
                            val body = api.firstString(o, "content", "message", "text", "body") ?: ""
                            val time = api.firstString(o, "created_at", "time", "date", "updated_at") ?: ""
                            Triple(title, body, time)
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                val posts = withContext(Dispatchers.IO) {
                    try {
                        val root = api.get("/forum/posts?page=1", requiresAuth = false)
                        val arr = api.extractList(root)
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val title = api.firstString(o, "title", "name", "subject") ?: "帖子"
                            val body = api.firstString(o, "content", "message", "text", "body") ?: ""
                            val author = api.firstString(o, "author", "username", "user", "nickname") ?: ""
                            val likes = o.optInt("likes", o.optInt("like_count", 0))
                            val time = api.firstString(o, "created_at", "time", "date", "updated_at") ?: ""
                            PostItem(title, body, author, likes, time)
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

                container.removeAllViews()
                if (announcements.isEmpty() && posts.isEmpty()) {
                    tvHint.text = "暂时无法加载社区内容，下拉刷新重试"
                    if (loaded) {
                        addCard("加载失败", "网络不可用或服务器暂时无法访问，请稍后重试", "")
                    }
                } else {
                    if (announcements.isNotEmpty()) {
                        addSectionTitle("公告")
                        announcements.forEach { (title, body, time) ->
                            addCard(title, body, time)
                        }
                    }
                    if (posts.isNotEmpty()) {
                        addSectionTitle("论坛")
                        posts.forEach { post ->
                            addCard(
                                post.title,
                                buildString {
                                    if (post.author.isNotBlank()) append(post.author).append(" · ")
                                    append(post.body)
                                },
                                buildString {
                                    append("赞 ")
                                    append(post.likes)
                                    if (post.time.isNotBlank()) append(" · ").append(post.time)
                                }
                            )
                        }
                    }
                    tvHint.text = "来自星灯云浪的最新公告与讨论"
                }
                loaded = true
            } finally {
                loading = false
            }
        }
    }

    private data class PostItem(
        val title: String,
        val body: String,
        val author: String,
        val likes: Int,
        val time: String
    )

    private fun dip(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun addSectionTitle(text: String) {
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(0xFFA0C4FF.toInt())
            textSize = 14f
            setPadding(dip(2), dip(14), 0, dip(8))
        }
        container.addView(tv)
    }

    private fun addCard(title: String, body: String, meta: String) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_nebula_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dip(10) }
            setPadding(dip(14), dip(12), dip(14), dip(12))
        }
        val tvTitle = TextView(activity).apply {
            text = title
            setTextColor(0xFFEDEDED.toInt())
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(tvTitle)
        if (body.isNotBlank()) {
            val tvBody = TextView(activity).apply {
                text = body.take(140)
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 12f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dip(6), 0, 0)
            }
            card.addView(tvBody)
        }
        if (meta.isNotBlank()) {
            val tvMeta = TextView(activity).apply {
                text = meta
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 11f
                setPadding(0, dip(6), 0, 0)
            }
            card.addView(tvMeta)
        }
        container.addView(card)
    }
}