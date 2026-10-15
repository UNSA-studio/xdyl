package www.xdyl.hygge.com

import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 社区功能集合（支持两种呈现方式）：
 * · 页面模式：renderXxxPage(activity, container) —— 直接渲染进社区页内容容器（整页）
 * · 弹窗模式：listDialog(...)                    —— 需要时弹出的列表
 *
 * 覆盖接口：
 *  /forum/categories  /memorials  /server/players  /tasks(+claim)  /user/items
 *  /titles/mine  /titles/catalog  /titles/wear  /titles/buy
 *  /user/change-username  /user/password
 *  /forum/post/{id}/tip  /notifications/read  /shop/buy  /redeem/game-coins
 */
object FeatureDialogs {

    /** 由 MainActivity 注入的 API 客户端 */
    lateinit var api: ApiClient

    // ==================== 通用工具 ====================

    private fun dp(a: AppCompatActivity, v: Int): Int =
        (v * a.resources.displayMetrics.density).toInt()

    /** 归一化 data → JSONArray（兼容多种包裹方式） */
    private fun toArray(root: JSONObject): JSONArray? {
        val data = root.opt("data")
        return when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("posts")
                ?: data.optJSONArray("players")
                ?: data.optJSONArray("titles")
                ?: data.optJSONArray("catalog")
                ?: data.optJSONArray("shop")
                ?: data.optJSONArray("data")
                ?: run {
                    // 兜底：取 data 里第一个非空数组
                    for (k in data.keys()) {
                        val a = data.optJSONArray(k)
                        if (a != null && a.length() > 0) return a
                    }
                    null
                }
            else -> root.optJSONArray("data")
        }
    }

    /** 剥离服务端数据里自带的 emoji / 装饰符号（按 Unicode 码点过滤，避免正则转义问题） */
    private fun stripEmoji(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isEmojiOrSymbol(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().trim()
    }

    private fun isEmojiOrSymbol(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||   // Emoji 区
        cp in 0x2600..0x27BF ||     // 杂项符号 / 装饰符号
        cp in 0x2B00..0x2BFF ||     // 杂项符号与箭头
        cp in 0xFE00..0xFE0F ||     // 变体选择符
        cp == 0x200D ||             // 零宽连接符
        cp in 0x1F1E6..0x1F1FF ||   // 区域指示符（旗帜）
        cp in 0x2190..0x21FF        // 箭头

    private fun itemTitle(o: JSONObject): String =
        stripEmoji(firstNonBlank(o, "title", "name", "nickname", "username", "player_name", "id") ?: "条目")

    private fun itemSubtitle(o: JSONObject): String {
        val parts = mutableListOf<String>()
        firstNonBlank(o, "description", "desc", "content", "subtitle", "detail")?.let { parts.add(it.take(80)) }
        firstNonBlank(o, "reward_coins", "reward")?.let { parts.add("奖励 $it 喵币") }
        firstNonBlank(o, "price", "cost", "price_coins", "need_coins", "coin_cost", "cost_coins")
            ?.let { parts.add("价格 $it 喵币") }
        firstNonBlank(o, "created_at", "time", "date")?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)?.toString()?.trim() ?: continue
            if (v.isNotEmpty() && v != "null" && v != "0") return v
        }
        return null
    }

    private fun textView(a: AppCompatActivity, s: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(a).apply {
            textSize = size
            setTextColor(color)
            RichText.apply(this, s)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    /** 统一卡片（列表项） */
    private fun makeCard(
        a: AppCompatActivity,
        title: String,
        subtitle: String,
        onClick: (() -> Unit)?
    ): LinearLayout {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(a, 14).toFloat()
                setColor(0xFF2A2A2A.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(a, 10) }
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick() }
        }
        card.addView(textView(a, title, 15f, 0xFFA0C4FF.toInt(), bold = true))
        if (subtitle.isNotBlank()) {
            card.addView(textView(a, subtitle, 12f, 0xFF9AA0A6.toInt()).apply {
                setPadding(0, dp(a, 6), 0, 0)
            })
        }
        return card
    }

    /** 带操作按钮的卡片：左侧文字 + 右侧按钮（支持禁用=灰色不可点） */
    private fun makeCardWithAction(
        a: AppCompatActivity,
        title: String,
        subtitle: String,
        actionText: String,
        onAction: () -> Unit,
        enabled: Boolean = true,
        disabledTip: String? = null
    ): LinearLayout {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(a, 14).toFloat()
                setColor(0xFF2A2A2A.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(a, 10) }
        }
        val textCol = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(textView(a, title, 15f, 0xFFA0C4FF.toInt(), bold = true))
        if (subtitle.isNotBlank()) {
            textCol.addView(textView(a, subtitle, 12f, 0xFF9AA0A6.toInt()).apply {
                setPadding(0, dp(a, 6), 0, 0)
            })
        }
        card.addView(textCol)
        card.addView(MaterialButton(a).apply {
            text = actionText
            textSize = 13f
            minWidth = 0
            setPadding(dp(a, 16), 0, dp(a, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 38)
            )
            if (enabled) {
                // 可点击：正常配色
                setTextColor(0xFF10131A.toInt())
                setBackgroundColor(0xFFA0C4FF.toInt())
                isEnabled = true
                setOnClickListener { onAction() }
            } else {
                // 灰色外观，但仍可点击 → 弹出提示
                setTextColor(0xFF6B6B6B.toInt())
                setBackgroundColor(0xFF3A3A3A.toInt())
                isEnabled = true
                setOnClickListener {
                    if (disabledTip != null) {
                        AppDialogs.tip(a, "尚未完成任务目标", disabledTip)
                    }
                }
            }
        })
        return card
    }

    // ==================== 页面模式（整页渲染） ====================

    /**
     * 通用功能页：渲染到社区页的 featureContainer 中。
     * 自带「标题 + 刷新按钮 + 可滚动列表」。
     */
    fun renderPage(
        activity: AppCompatActivity,
        container: LinearLayout,
        title: String,
        path: String,
        actionText: String? = null,
        itemMapper: ((JSONObject) -> Triple<String, String, (() -> Unit)?>)? = null
    ) {
        container.removeAllViews()

        val listCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        head.addView(textView(activity, title, 18f, 0xFFA0C4FF.toInt(), bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val refresh = MaterialButton(activity).apply {
            text = "刷新"
            textSize = 12f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 36)
            )
        }
        head.addView(refresh)
        container.addView(head)
        container.addView(listCol)

        fun doLoad() {
            listCol.removeAllViews()
            listCol.addView(textView(activity, "加载中…", 13f, 0xFF9AA0A6.toInt()))
            activity.lifecycleScope.launch {
                try {
                    val root = withContext(Dispatchers.IO) { api.get(path) }
                    LogManager.log("[PAGE] $path => " + root.toString().take(500))
                    val arr = toArray(root)
                    listCol.removeAllViews()
                    if (arr == null || arr.length() == 0) {
                        listCol.addView(textView(activity, "暂无数据", 13f, 0xFF9AA0A6.toInt()))
                        return@launch
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val mapped = itemMapper?.invoke(o)
                            ?: Triple(itemTitle(o), itemSubtitle(o), null)
                        if (mapped.third != null && actionText != null) {
                            // 带按钮（购买 / 佩戴等）
                            listCol.addView(
                                makeCardWithAction(
                                    activity, mapped.first, mapped.second, actionText,
                                    mapped.third!!
                                )
                            )
                        } else {
                            listCol.addView(makeCard(activity, mapped.first, mapped.second, mapped.third))
                        }
                    }
                } catch (e: Exception) {
                    listCol.removeAllViews()
                    handleError(activity, e)
                    listCol.addView(textView(activity, "加载失败：" + e.message, 13f, 0xFFE57373.toInt()))
                }
            }
        }
        refresh.setOnClickListener { doLoad() }
        doLoad()
    }

    /** 任务中心（页面）：完成的点一下领取 */
    fun renderTasksPage(activity: AppCompatActivity, container: LinearLayout) {
        container.removeAllViews()
        val listCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        head.addView(textView(activity, "任务中心", 18f, 0xFFA0C4FF.toInt(), bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val refresh = MaterialButton(activity).apply {
            text = "刷新"
            textSize = 12f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 36)
            )
        }
        head.addView(refresh)
        container.addView(head)
        container.addView(listCol)

        fun doLoad() {
            listCol.removeAllViews()
            listCol.addView(textView(activity, "加载中…", 13f, 0xFF9AA0A6.toInt()))
            activity.lifecycleScope.launch {
                try {
                    val root = withContext(Dispatchers.IO) { api.get("/tasks") }
                    val arr = toArray(root)
                    listCol.removeAllViews()
                    if (arr == null || arr.length() == 0) {
                        listCol.addView(textView(activity, "暂无任务", 13f, 0xFF9AA0A6.toInt()))
                        return@launch
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        // 真实字段：is_completed / progress / target_count / reward_coins / reward_exp
                        val done = o.optInt("is_completed", 0) == 1 || o.optBoolean("is_completed", false) ||
                                o.optInt("completed", 0) == 1 || o.optBoolean("completed", false)
                        val claimed = o.optInt("claimed", 0) == 1 || o.optBoolean("claimed", false) ||
                                o.optInt("received", 0) == 1 || o.optBoolean("received", false)
                        LogManager.log("[TASK] $o")
                        val desc = firstNonBlank(o, "description", "desc", "content") ?: ""
                        val rewardCoins = o.optInt("reward_coins", 0)
                        val rewardExp = o.optInt("reward_exp", 0)
                        val progress = o.optInt("progress", 0)
                        val targetCount = o.optInt("target_count", 0)
                        val sub = buildString {
                            append(desc)
                            if (targetCount > 0) append(" · 进度 ").append(progress).append("/").append(targetCount)
                            if (rewardCoins > 0 || rewardExp > 0) {
                                append(" · 奖励 ")
                                if (rewardCoins > 0) append(rewardCoins).append(" 喵币")
                                if (rewardExp > 0) {
                                    if (rewardCoins > 0) append(" + ")
                                    append(rewardExp).append(" 经验")
                                }
                            }
                            if (claimed) {
                                append(" · 已领取")
                            } else if (done) {
                                append(" · 可领取")
                            }
                        }
                        val canClaim = done && !claimed && id > 0
                        val btnText = if (claimed) "已领取" else "领取"
                        listCol.addView(
                            makeCardWithAction(
                                activity, itemTitle(o), sub, btnText,
                                onAction = { if (canClaim) claimTask(activity, id, itemTitle(o)) },
                                enabled = canClaim,
                                disabledTip = if (claimed) null else "完成之后再来领取吧"
                            )
                        )
                    }
                } catch (e: Exception) {
                    listCol.removeAllViews()
                    handleError(activity, e)
                    listCol.addView(textView(activity, "加载失败：" + e.message, 13f, 0xFFE57373.toInt()))
                }
            }
        }
        refresh.setOnClickListener { doLoad() }
        doLoad()
    }

    /** 我的称号（页面）：点一下佩戴 */
    fun renderTitlesPage(activity: AppCompatActivity, container: LinearLayout) {
        renderPage(activity, container, "我的称号", "/titles/mine", actionText = "佩戴") { o ->
            val id = o.optInt("id", -1)
            val worn = o.optInt("worn", 0) == 1 || o.optBoolean("worn", false)
            val sub = buildString {
                append(itemSubtitle(o))
                if (worn) append(" · 佩戴中")
            }
            Triple(
                itemTitle(o), sub,
                if (!worn && id > 0) ({ wearTitle(activity, id, itemTitle(o)); }) else null
            )
        }
    }

    /** 账户信息（页面）：改用户名 / 改密码 */
    fun renderAccountPage(activity: AppCompatActivity, container: LinearLayout) {
        container.removeAllViews()
        container.addView(
            textView(activity, "更改账户信息", 18f, 0xFFA0C4FF.toInt(), bold = true).apply {
                setPadding(0, dp(activity, 4), 0, dp(activity, 12))
            }
        )
        container.addView(
            makeCard(activity, "修改用户名", "修改你的账号昵称", { changeUsername(activity) })
        )
        container.addView(
            makeCard(activity, "修改密码", "修改登录密码", { changePassword(activity) })
        )
    }

    /** 在线玩家（页面）：显示 name / username */
    fun renderPlayersPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "在线玩家", "/server/players") { o ->
            val name = firstNonBlank(o, "name", "player_name", "nickname", "username") ?: "未知玩家"
            val account = firstNonBlank(o, "username", "account") ?: ""
            val sub = if (account.isNotBlank() && account != name) "账号：$account" else ""
            Triple(name, sub, null)
        }

    /** 纪念堂（页面） */
    fun renderMemorialsPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "纪念堂", "/memorials")

    /** 我的物品（页面） */
    fun renderMyItemsPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "我的物品", "/user/items")

    // ==================== 第一批新功能 ====================

    /** 时长奖励（页面） /playtime/rewards
     *  真实结构：data.milestones[] = { hours, coins, exp, reached, claimable, claimed }
     *  claim 参数：{"hours": N}
     */
    fun renderPlaytimeRewardsPage(activity: AppCompatActivity, container: LinearLayout) {
        container.removeAllViews()
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        head.addView(textView(activity, "时长奖励", 18f, 0xFFA0C4FF.toInt(), bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val refresh = MaterialButton(activity).apply {
            text = "刷新"
            textSize = 12f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 36)
            )
        }
        head.addView(refresh)
        container.addView(head)
        val listCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listCol)

        fun load() {
            listCol.removeAllViews()
            listCol.addView(textView(activity, "加载中…", 13f, 0xFF9AA0A6.toInt()))
            activity.lifecycleScope.launch {
                try {
                    val root = withContext(Dispatchers.IO) { api.get("/playtime/rewards") }
                    LogManager.log("[PAGE] /playtime/rewards => " + root.toString().take(400))
                    val data = root.optJSONObject("data")
                    val arr = data?.optJSONArray("milestones")
                    val totalSec = data?.optLong("total_seconds", 0L) ?: 0L
                    listCol.removeAllViews()
                    listCol.addView(
                        textView(
                            activity,
                            "累计游戏时长：" + (totalSec / 3600) + " 小时 " + ((totalSec % 3600) / 60) + " 分",
                            13f, 0xFF9AA0A6.toInt()
                        ).apply { setPadding(0, 0, 0, dp(activity, 10)) }
                    )
                    if (arr == null || arr.length() == 0) {
                        listCol.addView(textView(activity, "暂无数据", 13f, 0xFF9AA0A6.toInt()))
                        return@launch
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val hours = o.optInt("hours", 0)
                        val coins = o.optInt("coins", 0)
                        val exp = o.optInt("exp", 0)
                        val reached = o.optBoolean("reached", false)
                        val claimed = o.optBoolean("claimed", false)
                        val claimable = !claimed && (o.optBoolean("claimable", false) || reached)
                        val title = "累计 $hours 小时"
                        val sub = buildString {
                            append("奖励 ").append(coins).append(" 喵币")
                            if (exp > 0) append(" + ").append(exp).append(" 经验")
                            append(if (reached) " · 已达成" else " · 未达成")
                            if (claimed) append(" · 已领取") else if (claimable) append(" · 可领取")
                        }
                        val btnText = if (claimed) "已领取" else "领取"
                        listCol.addView(
                            makeCardWithAction(
                                activity, title, sub, btnText,
                                onAction = {
                                    if (claimable) {
                                        postJsonThen(
                                            activity, "/playtime/rewards/claim",
                                            JSONObject().put("hours", hours), "领取奖励"
                                        ) { load() }
                                    }
                                },
                                enabled = claimable,
                                disabledTip = if (claimed) null else "完成之后再来领取吧"
                            )
                        )
                    }
                } catch (e: Exception) {
                    handleError(activity, e)
                    listCol.removeAllViews()
                    listCol.addView(textView(activity, "加载失败：" + e.message, 13f, 0xFFE57373.toInt()))
                }
            }
        }
        refresh.setOnClickListener { load() }
        load()
    }

    /** 市场行情（页面） /market/preview */
    fun renderMarketPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "市场行情", "/market/preview") { o ->
            val name = firstNonBlank(o, "name", "item_name", "id") ?: "物品"
            val buy = o.optDouble("buy", 0.0)
            val change = o.optDouble("change", 0.0)
            val arrow = if (change > 0) "↑" else if (change < 0) "↓" else "—"
            val sub = "买价 " + String.format(java.util.Locale.US, "%.3f", buy) +
                    " · " + arrow + " " + String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(change)) + "%"
            Triple(name, sub, null)
        }

    /** 投票（页面 + 投票按钮） /polls */
    fun renderPollsPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "投票", "/polls", actionText = "投票") { o ->
            val id = o.optInt("id", -1)
            Triple(itemTitle(o), itemSubtitle(o), {
                listDialog(
                    activity, "投票：" + itemTitle(o),
                    listOf(
                        Triple("赞成", "", { postJsonThen(activity, "/polls/$id", JSONObject().put("option", "yes"), "投票", null) }),
                        Triple("反对", "", { postJsonThen(activity, "/polls/$id", JSONObject().put("option", "no"), "投票", null) })
                    )
                )
            })
        }

    /** 周目（页面） /seasons */
    fun renderSeasonsPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "周目", "/seasons")

    /** 意见箱（页面 + 提交意见） /suggestions */
    fun renderSuggestionsPage(activity: AppCompatActivity, container: LinearLayout) {
        container.removeAllViews()
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        head.addView(textView(activity, "意见箱", 18f, 0xFFA0C4FF.toInt(), bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(MaterialButton(activity).apply {
            text = "提交意见"
            textSize = 12f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 36)
            )
            setOnClickListener {
                val et = EditText(activity).apply {
                    hint = "写下你的建议…"
                    minLines = 4
                    gravity = android.view.Gravity.TOP
                    setTextColor(0xFFEDEDED.toInt())
                    setHintTextColor(0xFF6B6B6B.toInt())
                }
                MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
                    .setTitle("提交意见")
                    .setView(et)
                    .setPositiveButton("提交") { _, _ ->
                        val t = et.text.toString().trim()
                        if (t.isEmpty()) return@setPositiveButton
                        postJsonThen(activity, "/suggestions", JSONObject().put("content", t), "提交意见") {
                            renderSuggestionsPage(activity, container)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        container.addView(head)

        val listCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listCol)
        listCol.addView(textView(activity, "加载中…", 13f, 0xFF9AA0A6.toInt()))
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get("/suggestions") }
                LogManager.log("[PAGE] /suggestions => " + root.toString().take(400))
                val arr = toArray(root)
                listCol.removeAllViews()
                if (arr == null || arr.length() == 0) {
                    listCol.addView(textView(activity, "暂无意见", 13f, 0xFF9AA0A6.toInt()))
                } else {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        listCol.addView(makeCard(activity, itemTitle(o), itemSubtitle(o), null))
                    }
                }
            } catch (e: Exception) {
                listCol.removeAllViews()
                handleError(activity, e)
                listCol.addView(textView(activity, "加载失败：" + e.message, 13f, 0xFFE57373.toInt()))
            }
        }
    }

    /** POST 并支持成功回调 */
    private fun postJsonThen(
        activity: AppCompatActivity,
        path: String,
        body: JSONObject,
        label: String,
        onDone: (() -> Unit)?
    ) {
        activity.lifecycleScope.launch {
            try {
                val r = api.request("POST", path, body)
                Toast.makeText(activity, "$label：" + friendly(r, "成功"), Toast.LENGTH_LONG).show()
                onDone?.invoke()
            } catch (e: Exception) {
                Toast.makeText(activity, "${label}失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 第二批新功能 ====================

    /** 统一错误处理：登录失效 → 清理会话并提示重新登录 */
    private fun handleError(activity: AppCompatActivity, e: Exception) {
        val msg = e.message ?: "未知错误"
        if (msg.contains("登录已失效") || msg.contains("未登录") || msg.contains("token") || msg.contains("Token")) {
            (activity as? MainActivity)?.onSessionExpired()
        } else {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** 在容器末尾追加一个"标题 + 列表"区块（不清空容器，用于多区块页面） */
    private fun appendSection(
        activity: AppCompatActivity,
        container: LinearLayout,
        title: String,
        path: String,
        actionText: String? = null,
        itemMapper: ((JSONObject) -> Triple<String, String, (() -> Unit)?>)? = null
    ) {
        container.addView(textView(activity, title, 16f, 0xFFA0C4FF.toInt(), bold = true).apply {
            setPadding(0, dp(activity, 12), 0, dp(activity, 8))
        })
        val listCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listCol)
        listCol.addView(textView(activity, "加载中…", 12f, 0xFF9AA0A6.toInt()))
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get(path) }
                LogManager.log("[PAGE] $path => " + root.toString().take(300))
                val arr = toArray(root)
                listCol.removeAllViews()
                if (arr == null || arr.length() == 0) {
                    listCol.addView(textView(activity, "暂无数据", 12f, 0xFF9AA0A6.toInt()))
                    return@launch
                }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val m = itemMapper?.invoke(o) ?: Triple(itemTitle(o), itemSubtitle(o), null)
                    if (m.third != null && actionText != null) {
                        listCol.addView(makeCardWithAction(activity, m.first, m.second, actionText, m.third!!))
                    } else {
                        listCol.addView(makeCard(activity, m.first, m.second, m.third))
                    }
                }
            } catch (e: Exception) {
                listCol.removeAllViews()
                handleError(activity, e)
                listCol.addView(textView(activity, "加载失败：" + e.message, 12f, 0xFFE57373.toInt()))
            }
        }
    }

    /** 决斗场（页面）：对齐朋友端布局 —— 我的排位 / 近期对局 / 排位匹配 / 在线对手 / 收到的挑战 / 排行榜 */
    fun renderPvpPage(activity: AppCompatActivity, container: LinearLayout) {
        container.removeAllViews()

        // 1) 我的排位
        appendSection(activity, container, "我的排位", "/pvp/me") { o ->
            val rank = o.optInt("rank_position", o.optInt("rank", o.optInt("position", 0)))
            val name = firstNonBlank(o, "name", "nickname", "username", "player_name") ?: ""
            val tier = firstNonBlank(o, "tier", "rank_name", "dan") ?: ""
            val pts = o.optInt("rating", o.optInt("points", o.optInt("score", 0)))
            val peak = o.optInt("peak_rating", o.optInt("max_points", o.optInt("highest", 0)))
            val wins = o.optInt("wins", o.optInt("win_count", 0))
            val losses = o.optInt("losses", o.optInt("lose_count", 0))
            val matches = o.optInt("matches", o.optInt("total", o.optInt("games", 0)))
            val totalPlayers = o.optInt("total_players", 0)
            val nextTier = o.optJSONObject("next_tier")
            val nextTierName = nextTier?.optString("tier", "") ?: ""
            val nextNeed = nextTier?.optInt("need", 0) ?: 0
            val title = buildString {
                append("#").append(rank)
                if (totalPlayers > 0) append(" / ").append(totalPlayers)
                if (name.isNotBlank()) append(" ").append(name)
                if (tier.isNotBlank()) append(" · ").append(tier)
                append(" · ").append(pts).append(" 分")
            }
            val sub = buildString {
                append(wins).append("胜 · ").append(losses).append("负 · ").append(matches).append("场")
                if (peak > 0) append(" · 最高 ").append(peak).append(" 分")
                if (nextTierName.isNotBlank()) append(" · 距").append(nextTierName).append("还需 ").append(nextNeed)
            }
            Triple(title, sub, null)
        }

        // 2) 近期对局
        appendSection(activity, container, "近期对局", "/pvp/history")

        // 3) 排位匹配（自定义区块：绑定提示 + 偏好 + 状态 + 开始匹配）
        container.addView(textView(activity, "排位匹配", 16f, 0xFFA0C4FF.toInt(), bold = true).apply {
            setPadding(0, dp(activity, 12), 0, dp(activity, 8))
        })
        val matchInfo = textView(activity, "加载中…", 13f, 0xFF9AA0A6.toInt())
        container.addView(matchInfo)
        val matchBtn = MaterialButton(activity).apply {
            text = "开始匹配"
            textSize = 13f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40)
            ).apply { topMargin = dp(activity, 10) }
            setOnClickListener {
                postJsonThen(activity, "/pvp/match/join", JSONObject(), "开始匹配") {
                    renderPvpPage(activity, container)
                }
            }
        }
        container.addView(matchBtn)
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get("/pvp/match/status") }
                LogManager.log("[PAGE] /pvp/match/status => " + root.toString().take(300))
                val d = root.optJSONObject("data") ?: root
                val bound = d.optBoolean("bound", false) || d.optBoolean("game_bound", false)
                val pref = firstNonBlank(d, "time_preference", "preference", "time") ?: "未设置"
                val rawState = firstNonBlank(d, "state", "status") ?: ""
                val status = when (rawState.lowercase()) {
                    "idle" -> "未在匹配"
                    "matching" -> "匹配中"
                    "matched" -> "已匹配"
                    "in_game", "playing" -> "对局中"
                    else -> rawState.ifBlank { "未知" }
                }
                matchInfo.text = buildString {
                    if (!bound) append("请先在游戏内绑定游戏账号\n\n")
                    append("时间偏好：").append(pref).append("\n\n")
                    append("状态：").append(status)
                }
            } catch (e: Exception) {
                handleError(activity, e)
                matchInfo.text = "加载失败：" + e.message
            }
        }

        // 4) 在线对手（发起挑战）
        appendSection(activity, container, "发起挑战（在线对手）", "/pvp/online", actionText = "挑战") { o ->
            val name = firstNonBlank(o, "player_name", "name", "nickname", "username") ?: "玩家"
            val id = o.optInt("id", o.optInt("account_id", -1))
            Triple(name, itemSubtitle(o), {
                postJsonThen(
                    activity, "/pvp/challenge",
                    JSONObject().put("opponent_id", id).put("target_id", id),
                    "发起挑战", null
                )
            })
        }

        // 5) 挑战（收到的 / 已发出的）
        container.addView(textView(activity, "挑战", 16f, 0xFFA0C4FF.toInt(), bold = true).apply {
            setPadding(0, dp(activity, 12), 0, dp(activity, 8))
        })
        val chalCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(chalCol)
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get("/pvp/incoming") }
                LogManager.log("[PAGE] /pvp/incoming => " + root.toString().take(300))
                val d = root.optJSONObject("data")
                val incoming = d?.optJSONArray("incoming")
                val outgoing = d?.optJSONArray("outgoing")

                fun renderList(title: String, arr: org.json.JSONArray?, canAccept: Boolean) {
                    chalCol.addView(textView(activity, title, 13f, 0xFF9AA0A6.toInt()).apply {
                        setPadding(0, dp(activity, 8), 0, dp(activity, 4))
                    })
                    if (arr == null || arr.length() == 0) {
                        chalCol.addView(textView(activity, "暂无", 12f, 0xFF6B6B6B.toInt()))
                        return
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        val t = itemTitle(o)
                        val s = itemSubtitle(o)
                        if (canAccept && id > 0) {
                            chalCol.addView(makeCardWithAction(activity, t, s, "接受", {
                                postJsonThen(
                                    activity, "/pvp/challenge/respond",
                                    JSONObject().put("challenge_id", id).put("accept", true),
                                    "接受挑战", null
                                )
                            }))
                        } else {
                            chalCol.addView(makeCard(activity, t, s, null))
                        }
                    }
                }
                renderList("收到的挑战", incoming, true)
                renderList("已发出的挑战", outgoing, false)
            } catch (e: Exception) {
                handleError(activity, e)
                chalCol.addView(textView(activity, "加载失败：" + e.message, 12f, 0xFFE57373.toInt()))
            }
        }

        // 6) 排行榜
        appendSection(activity, container, "排行榜", "/pvp/rank") { o ->
            val rank = o.optInt("rank", o.optInt("position", 0))
            val name = firstNonBlank(o, "nickname", "player_name", "username", "name", "player")
                ?: ""
            val tier = firstNonBlank(o, "tier", "rank_name", "dan", "level_name", "level") ?: ""
            val pts = o.optInt("points", o.optInt("score", o.optInt("rating", o.optInt("mmr", 0))))
            val title = buildString {
                append("#").append(rank)
                if (name.isNotBlank()) append(" ").append(name)
                if (tier.isNotBlank()) append(" · ").append(tier)
                append(" · ").append(pts).append(" 分")
            }
            Triple(title, itemSubtitle(o), null)
        }
    }

    /** YSM 皮肤（页面）：刷新 / 上传 / 列表 */
    fun renderYsmPage(activity: AppCompatActivity, container: LinearLayout) {
        container.removeAllViews()
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        head.addView(textView(activity, "YSM 皮肤", 18f, 0xFFA0C4FF.toInt(), bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val listCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(head)
        container.addView(listCol)

        fun load() {
            listCol.removeAllViews()
            listCol.addView(textView(activity, "加载中…", 13f, 0xFF9AA0A6.toInt()))
            activity.lifecycleScope.launch {
                try {
                    val root = withContext(Dispatchers.IO) { api.get("/user/items") }
                    LogManager.log("[PAGE] /user/items => " + root.toString().take(500))
                    val arr = toArray(root)
                    listCol.removeAllViews()
                    if (arr == null || arr.length() == 0) {
                        listCol.addView(textView(activity, "暂无 YSM 皮肤", 13f, 0xFF9AA0A6.toInt()))
                        return@launch
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = firstNonBlank(o, "name", "title", "item_name", "filename") ?: "皮肤"
                        val sub = itemSubtitle(o)
                        listCol.addView(makeCard(activity, name, sub, null))
                    }
                } catch (e: Exception) {
                    handleError(activity, e)
                    listCol.removeAllViews()
                    listCol.addView(textView(activity, "加载失败：" + e.message, 13f, 0xFFE57373.toInt()))
                }
            }
        }

        val uploadBtn = MaterialButton(activity).apply {
            text = "上传 YSM 皮肤"
            textSize = 13f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40)
            ).apply { bottomMargin = dp(activity, 12) }
            setOnClickListener {
                AppDialogs.tip(activity, "上传 YSM 皮肤", "请在游戏内使用上传功能，或联系服主开通 App 上传。")
            }
        }
        listCol.addView(uploadBtn)
        load()
    }

    /** 游戏账号绑定（页面 + 解除按钮） */
    fun renderGameBindingsPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "游戏账号绑定", "/user/game-bindings", actionText = "解除") { o ->
            val id = o.optInt("id", -1)
            Triple(
                itemTitle(o), itemSubtitle(o),
                if (id > 0) ({ postJsonThen(activity, "/user/game-bindings/delete", JSONObject().put("id", id), "解除绑定", null) }) else null
            )
        }

    /** 爱发电（页面 + 领取按钮） */
    fun renderAfdianPage(activity: AppCompatActivity, container: LinearLayout) {
        container.removeAllViews()
        container.addView(textView(activity, "爱发电", 18f, 0xFFA0C4FF.toInt(), bold = true).apply {
            setPadding(0, dp(activity, 4), 0, dp(activity, 10))
        })
        val tv = textView(activity, "加载中…", 13f, 0xFF9AA0A6.toInt())
        container.addView(tv)
        container.addView(MaterialButton(activity).apply {
            text = "领取赞助奖励"
            textSize = 13f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40)
            ).apply { topMargin = dp(activity, 10) }
            setOnClickListener {
                postJsonThen(activity, "/afdian/claim", JSONObject(), "领取赞助奖励", null)
            }
        })
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) {
                    api.request("POST", "/afdian/status", JSONObject())
                }
                LogManager.log("[PAGE] /afdian/status => " + root.toString().take(300))
                tv.text = friendly(root, "已加载")
            } catch (e: Exception) {
                tv.text = "加载失败：" + e.message
            }
        }
    }

    /** 密码找回（弹窗：邮箱 → 验证码 → 新密码） */
    fun showPasswordReset(activity: AppCompatActivity) {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
        }
        val etEmail = EditText(activity).apply {
            hint = "邮箱"
            setTextColor(0xFFEDEDED.toInt())
            setHintTextColor(0xFF6B6B6B.toInt())
        }
        val etCode = EditText(activity).apply {
            hint = "验证码"
            setTextColor(0xFFEDEDED.toInt())
            setHintTextColor(0xFF6B6B6B.toInt())
        }
        val etNew = EditText(activity).apply {
            hint = "新密码"
            setTextColor(0xFFEDEDED.toInt())
            setHintTextColor(0xFF6B6B6B.toInt())
        }
        col.addView(etEmail)
        col.addView(etCode)
        col.addView(etNew)

        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("重置密码")
            .setView(col)
            .setNeutralButton("发送验证码") { _, _ ->
                val em = etEmail.text.toString().trim()
                if (em.isNotEmpty()) {
                    postJsonThen(activity, "/send-verify-code", JSONObject().put("email", em), "发送验证码", null)
                } else {
                    Toast.makeText(activity, "请先填写邮箱", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("重置") { _, _ ->
                val em = etEmail.text.toString().trim()
                val code = etCode.text.toString().trim()
                val np = etNew.text.toString().trim()
                if (em.isEmpty() || code.isEmpty() || np.isEmpty()) {
                    Toast.makeText(activity, "邮箱/验证码/新密码都不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                postJsonThen(
                    activity, "/reset-password",
                    JSONObject().put("email", em).put("code", code).put("password", np),
                    "重置密码", null
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 论坛分类（页面） */
    fun renderCategoriesPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "论坛分类", "/forum/categories")

    /** 称号商店（页面） */
    fun renderTitleShopPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "称号商店", "/titles/catalog", actionText = "购买") { o ->
            val id = o.optInt("id", -1)
            Triple(itemTitle(o), itemSubtitle(o), { buyTitle(activity, id, itemTitle(o)); })
        }

    // ==================== 弹窗模式（保留） ====================

    fun listDialog(
        activity: AppCompatActivity,
        title: String,
        rows: List<Triple<String, String, (() -> Unit)?>>
    ) {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8))
        }
        val scroll = ScrollView(activity).apply { addView(col) }
        val dialog = MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        if (rows.isEmpty()) {
            col.addView(textView(activity, "暂无数据", 14f, 0xFF9AA0A6.toInt()))
        }
        for ((t, s, onClick) in rows) {
            col.addView(makeCard(activity, t, s, onClick))
        }
        dialog.show()
    }

    /** 我的称号（弹窗版，供「我的」页称号胶囊点击使用） */
    fun showMyTitles(activity: AppCompatActivity) {
        Toast.makeText(activity, "正在加载称号…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get("/titles/mine") }
                val arr = toArray(root)
                val rows = mutableListOf<Triple<String, String, (() -> Unit)?>>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        val worn = o.optInt("worn", 0) == 1 || o.optBoolean("worn", false)
                        val sub = buildString {
                            append(itemSubtitle(o))
                            if (worn) append(" · 佩戴中")
                        }
                        rows.add(
                            Triple(
                                itemTitle(o), sub,
                                if (!worn && id > 0) ({ wearTitle(activity, id, itemTitle(o)); }) else null
                            )
                        )
                    }
                }
                if (rows.isEmpty()) {
                    rows.add(Triple("还没有称号", "点下面进称号商店看看", { showTitleShop(activity) }))
                } else {
                    rows.add(0, Triple("称号商店", "购买新的称号", { showTitleShop(activity) }))
                }
                listDialog(activity, "我的称号（点击佩戴）", rows)
            } catch (e: Exception) {
                Toast.makeText(activity, "加载失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 称号商店（弹窗版） */
    private fun showTitleShop(activity: AppCompatActivity) {
        Toast.makeText(activity, "正在加载称号商店…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get("/titles/catalog") }
                val arr = toArray(root)
                val rows = mutableListOf<Triple<String, String, (() -> Unit)?>>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        rows.add(
                            Triple(itemTitle(o), itemSubtitle(o), { buyTitle(activity, id, itemTitle(o)); })
                        )
                    }
                }
                listDialog(activity, "称号商店（点击购买）", rows)
            } catch (e: Exception) {
                Toast.makeText(activity, "加载失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 账户设置（弹窗版，保留兼容） */
    fun showAccountSettings(activity: AppCompatActivity) {
        listDialog(
            activity, "更改账户信息",
            listOf(
                Triple("修改用户名", "修改你的账号昵称", { changeUsername(activity) }),
                Triple("修改密码", "修改登录密码", { changePassword(activity) })
            )
        )
    }

    // ==================== 动作实现 ====================

    private fun claimTask(activity: AppCompatActivity, id: Int, name: String) {
        activity.lifecycleScope.launch {
            try {
                val r = api.request("POST", "/tasks/$id/claim")
                Toast.makeText(activity, "已领取：$name（" + friendly(r, "成功") + "）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "领取失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun wearTitle(activity: AppCompatActivity, id: Int, name: String) {
        activity.lifecycleScope.launch {
            try {
                val body = JSONObject().put("title_id", id)
                val r = api.request("POST", "/titles/wear", body)
                Toast.makeText(activity, "已佩戴：$name（" + friendly(r, "成功") + "）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "佩戴失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buyTitle(activity: AppCompatActivity, id: Int, name: String) {
        activity.lifecycleScope.launch {
            try {
                val body = JSONObject().put("title_id", id)
                val r = api.request("POST", "/titles/buy", body)
                Toast.makeText(activity, "购买：$name（" + friendly(r, "成功") + "）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "购买失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun changeUsername(activity: AppCompatActivity) {
        val et = EditText(activity).apply { hint = "新的用户名" }
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("修改用户名")
            .setView(et)
            .setPositiveButton("提交") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                postJson(activity, "/user/change-username", JSONObject().put("username", name), "用户名")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun changePassword(activity: AppCompatActivity) {
        val et = EditText(activity).apply { hint = "新的密码" }
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("修改密码")
            .setView(et)
            .setPositiveButton("提交") { _, _ ->
                val pwd = et.text.toString().trim()
                if (pwd.isEmpty()) return@setPositiveButton
                postJson(activity, "/user/password", JSONObject().put("password", pwd), "密码")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 商城购买 /shop/buy */
    fun buyShopItem(activity: AppCompatActivity, itemId: Int, itemName: String) {
        postJson(
            activity, "/shop/buy",
            JSONObject().put("item_id", itemId).put("quantity", 1),
            "购买 $itemName"
        )
    }

    /** 兑换游戏币 /redeem/game-coins */
    fun redeemGameCoins(activity: AppCompatActivity, amount: Int) {
        postJson(activity, "/redeem/game-coins", JSONObject().put("amount", amount), "兑换游戏币")
    }

    /** 发布帖子：POST /forum/post {title, content, category_id...} */
    fun showNewPost(activity: AppCompatActivity, onDone: () -> Unit = {}) {
        var selectedCategoryId = -1
        var selectedCategoryName = ""

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
        }

        // 版块选择（点击弹出列表）
        val tvCategory = TextView(activity).apply {
            text = "版块：加载中…"
            textSize = 14f
            setTextColor(0xFFEDEDED.toInt())
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(activity, 10).toFloat()
                setColor(0xFF2A2A2A.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 10) }
        }
        val etTitle = EditText(activity).apply {
            hint = "标题"
            setTextColor(0xFFEDEDED.toInt())
            setHintTextColor(0xFF6B6B6B.toInt())
        }
        val etContent = EditText(activity).apply {
            hint = "正文（支持 Markdown 图片语法）"
            setTextColor(0xFFEDEDED.toInt())
            setHintTextColor(0xFF6B6B6B.toInt())
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        col.addView(tvCategory)
        col.addView(etTitle)
        col.addView(etContent)

        // 标记提示 + 插入颜色按钮
        val hintRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 8), 0, 0)
        }
        hintRow.addView(textView(activity, "支持 &#RRGGBB 颜色 / **加粗** / ~~删除线~~", 11f, 0xFF6B6B6B.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        hintRow.addView(MaterialButton(activity).apply {
            text = "插入颜色"
            textSize = 12f
            minWidth = 0
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)
            )
            setOnClickListener {
                val names = arrayOf("红 FF0000", "橙 FF8800", "黄 FFD700", "绿 00CC66", "蓝 3399FF", "紫 AA66FF", "白 FFFFFF")
                val hexes = arrayOf("FF0000", "FF8800", "FFD700", "00CC66", "3399FF", "AA66FF", "FFFFFF")
                MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
                    .setTitle("插入颜色")
                    .setItems(names) { _, w ->
                        val mark = "&#" + hexes[w]
                        val start = etContent.selectionStart
                        if (start >= 0) etContent.text.insert(start, mark) else etContent.append(mark)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        col.addView(hintRow)

        // 拉取版块列表
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get("/forum/categories") }
                val arr = toArray(root)
                val names = mutableListOf<String>()
                val ids = mutableListOf<Int>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        if (id <= 0) continue
                        ids.add(id)
                        names.add(itemTitle(o))
                    }
                }
                if (ids.isEmpty()) {
                    tvCategory.text = "版块：无可用版块"
                    return@launch
                }
                selectedCategoryId = ids[0]
                selectedCategoryName = names[0]
                tvCategory.text = "版块：${names[0]}"
                tvCategory.setOnClickListener {
                    MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
                        .setTitle("选择版块")
                        .setItems(names.toTypedArray()) { _, w ->
                            selectedCategoryId = ids[w]
                            selectedCategoryName = names[w]
                            tvCategory.text = "版块：${names[w]}"
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                tvCategory.text = "版块：加载失败（" + e.message + "）"
            }
        }

        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("发布帖子")
            .setView(col)
            .setPositiveButton("发布") { _, _ ->
                val title = etTitle.text.toString().trim()
                val content = etContent.text.toString().trim()
                if (selectedCategoryId <= 0) {
                    Toast.makeText(activity, "请先选择版块", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (title.isEmpty() || content.isEmpty()) {
                    Toast.makeText(activity, "标题和正文都不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 服务端要求 版块+标题+内容；字段名做多别名兼容
                val body = JSONObject()
                    .put("title", title)
                    .put("content", content)
                    .put("category_id", selectedCategoryId)
                    .put("category", selectedCategoryId)
                    .put("board_id", selectedCategoryId)
                    .put("board", selectedCategoryId)
                    .put("section_id", selectedCategoryId)
                activity.lifecycleScope.launch {
                    try {
                        api.request("POST", "/forum/post", body)
                        Toast.makeText(activity, "发布成功", Toast.LENGTH_SHORT).show()
                        onDone()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "发布失败：" + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 帖子打赏 /forum/post/{id}/tip */
    fun tipPost(activity: AppCompatActivity, postId: Int) {
        val et = EditText(activity).apply {
            hint = "打赏金额（喵币）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("打赏帖子")
            .setView(et)
            .setPositiveButton("打赏") { _, _ ->
                val amount = et.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                postJson(
                    activity, "/forum/post/$postId/tip",
                    JSONObject().put("amount", amount), "打赏 $amount 喵币"
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 通知标记已读 /notifications/read */
    fun markNotificationsRead(activity: AppCompatActivity, id: Int? = null) {
        val body = JSONObject()
        if (id != null) body.put("notification_id", id)
        postJson(activity, "/notifications/read", body, "通知已读")
    }

    /** 更换头像：本地选择 / 使用默认 */
    fun pickAvatar(activity: AppCompatActivity) {
        val items = arrayOf("从本地文件选择", "使用默认头像（无头像）")
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("更换头像")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> (activity as? MainActivity)?.startAvatarPick()
                    1 -> postJson(activity, "/user/avatar", JSONObject().put("avatar", ""), "使用默认头像")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把服务端响应解析成可读文案（取 message，并附带余额等附加信息） */
    private fun friendly(resp: JSONObject, fallback: String): String {
        val data = resp.optJSONObject("data")
        val msg = resp.optString("message", "").ifBlank {
            data?.optString("message", "") ?: ""
        }.ifBlank { fallback }
        // 附带余额（服务端可能返回 new_coins / coins / balance）
        val coins = when {
            resp.has("new_coins") -> resp.optInt("new_coins", -1)
            data?.has("new_coins") == true -> data.optInt("new_coins", -1)
            data?.has("coins") == true -> data.optInt("coins", -1)
            else -> -1
        }
        return if (coins >= 0) "$msg（余额 $coins 喵币）" else msg
    }

    /** 通用 POST JSON */
    fun postJson(activity: AppCompatActivity, path: String, body: JSONObject, label: String) {
        activity.lifecycleScope.launch {
            try {
                val r = api.request("POST", path, body)
                Toast.makeText(activity, "$label：" + friendly(r, "成功"), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "${label}失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}