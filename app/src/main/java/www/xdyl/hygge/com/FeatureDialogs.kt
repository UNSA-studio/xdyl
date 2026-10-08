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
 * 社区功能集合（按服务端接口清单扩展）：
 *  - 论坛分类      /forum/categories
 *  - 纪念堂        /memorials
 *  - 在线玩家      /server/players
 *  - 任务中心      /tasks + /tasks/{id}/claim
 *  - 我的称号      /titles/mine + /titles/catalog + /titles/wear + /titles/buy
 *  - 我的物品      /user/items
 *  - 账户设置      /user/change-username + /user/password
 *  - 帖子打赏      /forum/post/{id}/tip
 *  - 通知已读      /notifications/read
 *  - 商城购买      /shop/buy
 *  - 兑换游戏币    /redeem/game-coins
 */
object FeatureDialogs {

    /** 由 MainActivity 注入的 API 客户端 */
    lateinit var api: ApiClient

    // ==================== 通用工具 ====================

    /** 把接口返回的 data 归一化成 JSONArray（兼容 data 为数组 / data.items / data.list 等形式） */
    private fun toArray(root: JSONObject): JSONArray? {
        val data = root.opt("data")
        return when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("posts")
                ?: data.optJSONArray("players")
                ?: data.optJSONArray("data")
                ?: data.optJSONArray("titles")
            else -> root.optJSONArray("data")
        }
    }

    /** 条目渲染：优先常见字段，没有就展示条目的前若干字符（避免字段名猜错导致空白） */
    private fun itemTitle(o: JSONObject): String =
        firstNonBlank(o, "title", "name", "nickname", "username", "player_name", "id") ?: "条目"

    private fun itemSubtitle(o: JSONObject): String {
        val parts = mutableListOf<String>()
        firstNonBlank(o, "description", "desc", "content", "subtitle")?.let { parts.add(it.take(80)) }
        firstNonBlank(o, "reward", "coins", "price", "cost")?.let { parts.add("奖励/价格：$it") }
        firstNonBlank(o, "status", "state")?.let { parts.add(it) }
        if (parts.isEmpty()) {
            // 兜底：把 JSON 截断展示
            parts.add(o.toString().take(100))
        }
        return parts.joinToString(" · ")
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)?.toString()?.trim() ?: continue
            if (v.isNotEmpty() && v != "null" && v != "0") return v
        }
        return null
    }

    /** 通用滚动列表弹窗 */
    fun listDialog(
        activity: AppCompatActivity,
        title: String,
        rows: List<Triple<String, String, (() -> Unit)?>>
    ) {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val scroll = ScrollView(activity).apply { addView(col) }
        val dialog = MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()

        if (rows.isEmpty()) {
            col.addView(TextView(activity).apply {
                text = "暂无数据"
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 14f
            })
        }
        for ((t, s, onClick) in rows) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 22, 28, 22)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(0xFF2A2A2A.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 18 }
                isClickable = onClick != null
                if (onClick != null) setOnClickListener { onClick() }
            }
            card.addView(TextView(activity).apply {
                text = t
                setTextColor(0xFFA0C4FF.toInt())
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (s.isNotBlank()) {
                card.addView(TextView(activity).apply {
                    text = s
                    setTextColor(0xFF9AA0A6.toInt())
                    textSize = 12f
                    setPadding(0, 8, 0, 0)
                })
            }
            col.addView(card)
        }
        dialog.show()
    }

    /** 通用「加载中→列表」流程 */
    private fun load(
        activity: AppCompatActivity,
        loadingToast: String,
        path: String,
        title: String,
        itemMapper: ((JSONObject) -> Triple<String, String, (() -> Unit)?>)? = null
    ) {
        Toast.makeText(activity, loadingToast, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get(path) }
                val arr = toArray(root)
                val rows = mutableListOf<Triple<String, String, (() -> Unit)?>>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        rows.add(itemMapper?.invoke(o) ?: Triple(itemTitle(o), itemSubtitle(o), null))
                    }
                }
                listDialog(activity, title, rows)
            } catch (e: Exception) {
                Toast.makeText(activity, "加载失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 各功能 ====================

    /** 论坛分类 /forum/categories */
    fun showCategories(activity: AppCompatActivity) = load(
        activity, "正在加载分类…", "/forum/categories", "论坛分类"
    )

    /** 纪念堂 /memorials */
    fun showMemorials(activity: AppCompatActivity) = load(
        activity, "正在加载纪念堂…", "/memorials", "纪念堂"
    )

    /** 在线玩家 /server/players */
    fun showPlayers(activity: AppCompatActivity) = load(
        activity, "正在查询在线玩家…", "/server/players", "在线玩家"
    )

    /** 我的物品 /user/items */
    fun showMyItems(activity: AppCompatActivity) = load(
        activity, "正在加载物品…", "/user/items", "我的物品"
    )

    /** 任务中心 /tasks，点击可领取奖励 */
    fun showTasks(activity: AppCompatActivity) {
        Toast.makeText(activity, "正在加载任务…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { api.get("/tasks") }
                val arr = toArray(root)
                val rows = mutableListOf<Triple<String, String, (() -> Unit)?>>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        val done = o.optInt("completed", 0) == 1 || o.optBoolean("completed", false)
                        val claimed = o.optInt("claimed", 0) == 1 || o.optBoolean("claimed", false)
                        val sub = buildString {
                            append(itemSubtitle(o))
                            if (claimed) append(" · 已领取") else if (done) append(" · ✅ 可领取")
                        }
                        rows.add(
                            Triple(
                                itemTitle(o), sub,
                                if (done && !claimed && id > 0) {
                                    {
                                        claimTask(activity, id, itemTitle(o))
                                    }
                                } else null
                            )
                        )
                    }
                }
                listDialog(activity, "任务中心", rows)
            } catch (e: Exception) {
                Toast.makeText(activity, "加载失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

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

    /** 我的称号 /titles/mine */
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
                                if (!worn && id > 0) {
                                    { wearTitle(activity, id, itemTitle(o)) }
                                } else null
                            )
                        )
                    }
                }
                if (rows.isEmpty()) {
                    rows.add(Triple("还没有称号", "去称号商店看看吧", {
                        showTitleShop(activity)
                    }))
                } else {
                    rows.add(0, Triple("🏪 称号商店", "购买新的称号", { showTitleShop(activity) }))
                }
                listDialog(activity, "我的称号（点击佩戴）", rows)
            } catch (e: Exception) {
                Toast.makeText(activity, "加载失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showTitleShop(activity: AppCompatActivity) = load(
        activity, "正在加载称号商店…", "/titles/catalog", "称号商店（点击购买）",
        itemMapper = { o ->
            val id = o.optInt("id", -1)
            Triple(itemTitle(o), itemSubtitle(o), { buyTitle(activity, id, itemTitle(o)) })
        }
    )

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

    /** 账户设置：改用户名 / 改密码 */
    fun showAccountSettings(activity: AppCompatActivity) {
        listDialog(
            activity, "账户设置",
            listOf(
                Triple("修改用户名", "修改你的账号昵称", { changeUsername(activity) }),
                Triple("修改密码", "修改登录密码", { changePassword(activity) })
            )
        )
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
        val et = EditText(activity).apply { hint = "打赏金额（喵币）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
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
                Toast.makeText(activity, "$label失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}