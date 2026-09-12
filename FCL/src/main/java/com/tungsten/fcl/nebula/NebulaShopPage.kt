package com.tungsten.fcl.nebula

import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.NebulaMainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 商城页：兑换比例 + 商品列表 + 二次确认购买（需要登录星灯账号）。
 */
class NebulaShopPage(
    private val activity: NebulaMainActivity,
    root: View
) {
    private val container: LinearLayout = root.findViewById(R.id.shopContainer)
    private val tvRate: TextView = root.findViewById(R.id.tvRedeemRate)
    private val btnRefresh: View = root.findViewById(R.id.btnRefreshShop)

    private val session by lazy { SessionStore(activity) }
    private val api by lazy { ApiClient(session) }
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

    fun invalidate() {
        loaded = false
    }

    private fun load() {
        loading = true
        activity.lifecycleScope.launch {
            try {
                container.removeAllViews()
                // 兑换比例（匿名可读）
                withContext(Dispatchers.IO) {
                    try {
                        val root = api.get("/redeem/rate", requiresAuth = false)
                        val rate = root.optJSONObject("data")?.optInt("rate", 10) ?: 10
                        withContext(Dispatchers.Main) { tvRate.text = "兑换比例 1:$rate" }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { tvRate.text = "兑换比例未知" }
                    }
                }

                if (!session.isLoggedIn) {
                    addHint("商城需要登录后浏览\n\n点击「我的」页登录星灯账号")
                    loaded = true
                    return@launch
                }

                val items = withContext(Dispatchers.IO) {
                    val root = api.get("/shop/items")
                    api.extractList(root)
                }
                if (items.length() == 0) {
                    addHint("暂无商品")
                } else {
                    for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        addItemCard(
                            api.firstString(o, "name") ?: "商品",
                            api.firstString(o, "description") ?: "",
                            o.optInt("id", 0),
                            o.optInt("price", -1)
                        )
                    }
                }
                loaded = true
            } finally {
                loading = false
            }
        }
    }

    private fun dip(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun addHint(text: String) {
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(dip(16), dip(60), dip(16), 0)
        }
        container.addView(tv)
    }

    private fun addItemCard(name: String, desc: String, id: Int, price: Int) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_nebula_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dip(10) }
            setPadding(dip(14), dip(12), dip(14), dip(12))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(
            TextView(activity).apply {
                text = name
                setTextColor(0xFFEDEDED.toInt())
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        if (desc.isNotBlank()) {
            textCol.addView(
                TextView(activity).apply {
                    text = desc
                    setTextColor(0xFF9AA0A6.toInt())
                    textSize = 12f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dip(4), 0, 0)
                }
            )
        }
        if (price >= 0) {
            textCol.addView(
                TextView(activity).apply {
                    text = "价格 $price"
                    setTextColor(0xFFA0C4FF.toInt())
                    textSize = 12f
                    setPadding(0, dip(4), 0, 0)
                }
            )
        }
        row.addView(textCol)
        val btn = MaterialButton(activity).apply {
            text = "购买"
            textSize = 13f
            setTextColor(0xFF10131A.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            cornerRadius = dip(10)
            minWidth = 0
            setPadding(dip(16), 0, dip(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dip(36)
            )
            setOnClickListener { confirmBuy(name, desc, id) }
        }
        row.addView(btn)
        card.addView(row)
        container.addView(card)
    }

    private fun confirmBuy(name: String, desc: String, id: Int) {
        val message = if (desc.isBlank()) "购买「$name」？" else "购买「$name」？\n\n$desc"
        AlertDialog.Builder(activity)
            .setTitle("确认购买")
            .setMessage(message)
            .setPositiveButton("购买") { _, _ ->
                activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            api.post("/shop/buy", JSONObject().put("item_id", id))
                        }
                        Toast.makeText(activity, "购买成功", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "购买失败：" + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}