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
 *  · 页面模式：renderXxxPage(activity, container) —— 直接渲染进社区页内容容器（整页）
 *  · 弹窗模式：listDialog(...)                    —— 需要时弹出的列表
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

    /** 归一化 data → JSONArray */
    private fun toArray(root: JSONObject): JSONArray? {
        val data = root.opt("data")
        return when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("posts")
                ?: data.optJSONArray("players")
                ?: data.optJSONArray("titles")
                ?: data.optJSONArray("data")
            else -> root.optJSONArray("data")
        }
    }

    private fun itemTitle(o: JSONObject): String =
        firstNonBlank(o, "title", "name", "nickname", "username", "player_name", "id") ?: "条目"

    private fun itemSubtitle(o: JSONObject): String {
        val parts = mutableListOf<String>()
        firstNonBlank(o, "description", "desc", "content", "subtitle")?.let { parts.add(it.take(80)) }
        firstNonBlank(o, "reward", "coins", "price", "cost")?.let { parts.add("奖励/价格：$it") }
        firstNonBlank(o, "status", "state")?.let { parts.add(it) }
        if (parts.isEmpty()) parts.add(o.toString().take(100))
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
            text = s
            textSize = size
            setTextColor(color)
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
                        listCol.addView(makeCard(activity, mapped.first, mapped.second, mapped.third))
                    }
                } catch (e: Exception) {
                    listCol.removeAllViews()
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
                        val done = o.optInt("completed", 0) == 1 || o.optBoolean("completed", false)
                        val claimed = o.optInt("claimed", 0) == 1 || o.optBoolean("claimed", false)
                        val sub = buildString {
                            append(itemSubtitle(o))
                            if (claimed) append(" · 已领取")
                            else if (done) append(" · ✅ 点击领取")
                        }
                        listCol.addView(
                            makeCard(
                                activity, itemTitle(o), sub,
                                if (done && !claimed && id > 0) {
                                    { claimTask(activity, id, itemTitle(o)); }
                                } else null
                            )
                        )
                    }
                } catch (e: Exception) {
                    listCol.removeAllViews()
                    listCol.addView(textView(activity, "加载失败：" + e.message, 13f, 0xFFE57373.toInt()))
                }
            }
        }
        refresh.setOnClickListener { doLoad() }
        doLoad()
    }

    /** 我的称号（页面）：点一下佩戴 */
    fun renderTitlesPage(activity: AppCompatActivity, container: LinearLayout) {
        renderPage(activity, container, "我的称号（点击佩戴）", "/titles/mine") { o ->
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

    /** 在线玩家（页面） */
    fun renderPlayersPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "在线玩家", "/server/players")

    /** 纪念堂（页面） */
    fun renderMemorialsPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "纪念堂", "/memorials")

    /** 我的物品（页面） */
    fun renderMyItemsPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "我的物品", "/user/items")

    /** 论坛分类（页面） */
    fun renderCategoriesPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "论坛分类", "/forum/categories")

    /** 称号商店（页面） */
    fun renderTitleShopPage(activity: AppCompatActivity, container: LinearLayout) =
        renderPage(activity, container, "称号商店（点击购买）", "/titles/catalog") { o ->
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
                    rows.add(0, Triple("🏪 称号商店", "购买新的称号", { showTitleShop(activity) }))
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
                val r = api.request("POST", "/tasks/$id/claim").toString()
                Toast.makeText(activity, "已领取：$name\n$r", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "领取失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun wearTitle(activity: AppCompatActivity, id: Int, name: String) {
        activity.lifecycleScope.launch {
            try {
                val body = JSONObject().put("title_id", id)
                val r = api.request("POST", "/titles/wear", body).toString()
                Toast.makeText(activity, "已佩戴：$name\n$r", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "佩戴失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buyTitle(activity: AppCompatActivity, id: Int, name: String) {
        activity.lifecycleScope.launch {
            try {
                val body = JSONObject().put("title_id", id)
                val r = api.request("POST", "/titles/buy", body).toString()
                Toast.makeText(activity, "购买：$name\n$r", Toast.LENGTH_LONG).show()
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

    /** 通用 POST JSON */
    fun postJson(activity: AppCompatActivity, path: String, body: JSONObject, label: String) {
        activity.lifecycleScope.launch {
            try {
                val r = api.request("POST", path, body).toString()
                Toast.makeText(activity, "$label：$r", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "${label}失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}