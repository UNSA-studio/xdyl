package www.xdyl.hygge.com

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * 【私聊页面】
 *
 * 会话列表 → 点进某个会话 → 聊天窗口
 * 全部为代码构建 UI（与项目现有风格一致：深色卡片 + 浅蓝主色）
 */
object ChatPage {

    private const val BG = 0xFF1E1E1E.toInt()
    private const val CARD = 0xFF2A2A2A.toInt()
    private const val PRIMARY = 0xFFA0C4FF.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()
    private const val SUB = 0xFF888888.toInt()
    private const val BUBBLE_OUT = 0xFF2E4A6B.toInt()
    private const val BUBBLE_IN = 0xFF333333.toInt()

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    // ==================== 会话列表 ====================

    private var convRefreshHandler: android.os.Handler? = null
    private val convRefreshTask = object : Runnable {
        override fun run() {
            lastConvContainer?.let { c ->
                if (c.isAttachedToWindow) refreshConversationList(c)
            }
            convRefreshHandler?.postDelayed(this, 4000L)
        }
    }

    private var lastConvContainer: LinearLayout? = null

    /** 开始/停止会话列表自动刷新（每 4 秒） */
    fun startConversationAutoRefresh() {
        stopConversationAutoRefresh()
        convRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper()).also {
            it.postDelayed(convRefreshTask, 1500L)
        }
    }

    fun stopConversationAutoRefresh() {
        convRefreshHandler?.removeCallbacks(convRefreshTask)
        convRefreshHandler = null
    }

    /** 只刷新列表区（保留顶部状态栏），用于定时刷新 */
    private fun refreshConversationList(container: LinearLayout) {
        val peers = ChatStore.loadIndex(container.context)
        val existing = container.tag as? Int ?: -1
        if (existing == peers.hashCode()) return   // 无变化不重绘
        container.tag = peers.hashCode()
        while (container.childCount > 1) container.removeViewAt(container.childCount - 1)
        if (peers.isEmpty()) {
            container.addView(TextView(container.context).apply {
                text = "还没有会话\n点右上角「新建会话」输入对方用户 ID"
                setTextColor(SUB)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(container.context, 60), 0, 0)
            })
            return
        }
        peers.forEach { peer ->
            val card = makeCard(container.context as android.app.Activity as AppCompatActivity, peer)
            card.alpha = 0f
            card.translationY = dp(container.context, 8).toFloat()
            container.addView(card)
            card.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
    }

    fun renderConversations(activity: AppCompatActivity, container: LinearLayout) {
        lastConvContainer = container
        container.removeAllViews()
        container.setBackgroundColor(BG)

        // 顶部：服务器状态 + 新建会话
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 8))
        }
        val tvStatus = TextView(activity).apply {
            text = if (ChatApi.isConfigured(activity)) "私聊服务器：${ChatApi.serverBase(activity)}" else "私聊服务器未配置"
            setTextColor(if (ChatApi.isConfigured(activity)) SUB else 0xFFE57373.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNew = MaterialButton(activity).apply {
            text = "新建会话"
            textSize = 12f
            setTextColor(Color.BLACK)
            setBackgroundColor(PRIMARY)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
            setOnClickListener { showNewChatDialog(activity, container) }
        }
        header.addView(tvStatus)
        header.addView(btnNew)
        container.addView(header)

        val peers = ChatStore.loadIndex(activity)
        if (peers.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = "还没有会话\n点右上角「新建会话」输入对方用户 ID"
                setTextColor(SUB)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 60), 0, 0)
            })
            return
        }

        container.tag = peers.hashCode()
        peers.forEachIndexed { i, peer ->
            val card = makeCard(activity, peer)
            card.alpha = 0f
            card.translationY = dp(activity, 10).toFloat()
            container.addView(card)
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 35L).coerceAtMost(200L))
                .setDuration(180)
                .start()
        }
        startConversationAutoRefresh()
    }

    private fun makeCard(activity: AppCompatActivity, peer: ChatStore.Peer): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8) }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameBox = TextView(activity).apply {
            text = peer.nickname + "  (#${peer.userId})"
            setTextColor(TEXT)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameBox)
        if (peer.unread > 0) {
            row.addView(TextView(activity).apply {
                text = if (peer.unread > 99) "99+" else peer.unread.toString()
                setTextColor(Color.BLACK)
                textSize = 11f
                gravity = Gravity.CENTER
                setBackgroundColor(PRIMARY)
                setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 2))
            })
        }
        card.addView(row)

        card.addView(TextView(activity).apply {
            text = peer.lastText.ifBlank { "（暂无消息）" }
            setTextColor(SUB)
            textSize = 12f
            maxLines = 1
            setPadding(0, dp(activity, 4), 0, 0)
        })

        card.setOnClickListener {
            ChatStore.clearUnread(activity, peer.userId)
            openChat(activity, peer.userId, peer.nickname)
        }
        return card
    }

    private fun showNewChatDialog(activity: AppCompatActivity, container: LinearLayout) {
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), 0)
        }
        wrapper.addView(TextView(activity).apply {
            text = "对方用户 ID"
            setTextColor(SUB)
            textSize = 12f
        })
        val etId = EditText(activity).apply {
            hint = "例如 22"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT)
        }
        wrapper.addView(etId)
        wrapper.addView(TextView(activity).apply {
            text = "昵称（可留空）"
            setTextColor(SUB)
            textSize = 12f
            setPadding(0, dp(activity, 10), 0, 0)
        })
        val etName = EditText(activity).apply {
            hint = "备注名"
            setTextColor(TEXT)
        }
        wrapper.addView(etName)

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("新建私聊")
            .setView(wrapper)
            .setPositiveButton("开始") { _, _ ->
                val id = etId.text.toString().trim().toIntOrNull()
                if (id == null || id <= 0) {
                    AppDialogs.tip(activity, "ID 无效", "请输入纯数字的用户 ID")
                    return@setPositiveButton
                }
                val manual = etName.text.toString().trim()
                ChatStore.setPeerNickname(activity, id, manual.ifBlank { "用户$id" })
                // 先用本地昵称直接进聊天，后台再查公开资料补全真名
                openChat(activity, id, manual.ifBlank { "用户$id" })
                Thread {
                    val legacy = "https://login.lanternwaves.fun"
                    val info = ChatApi.fetchPlayerName(legacy, id.toString())
                    if (info != null) {
                        val (realName, registered) = info
                        if (manual.isBlank() && registered) {
                            ChatStore.setPeerNickname(activity, id, realName)
                            LogManager.i("[CHAT] 自动解析玩家 $id -> $realName")
                        }
                    } else {
                        LogManager.w("[CHAT] 玩家 $id 公开资料查询失败")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 聊天窗口 ====================

    private fun meId(activity: AppCompatActivity): Int {
        val prefs = activity.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        return prefs.getInt("my_user_id", 0)
    }

    private fun token(activity: AppCompatActivity): String {
        // 复用主会话的 access token（ChatApi 与服务端共享认证）
        return try {
            val session = SessionStore(activity)
            session.accessToken
        } catch (e: Exception) {
            ""
        }
    }

    fun openChat(activity: AppCompatActivity, peerId: Int, nickname: String) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // 顶部栏
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
        val tvTitle = TextView(activity).apply {
            text = "$nickname  (#$peerId)"
            setTextColor(PRIMARY)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = MaterialButton(activity).apply {
            text = "关闭"
            textSize = 12f
            setTextColor(PRIMARY)
            setBackgroundColor(0x00000000)
            minHeight = 0
            minimumHeight = 0
        }
        top.addView(tvTitle)
        top.addView(btnClose)
        root.addView(top)

        // 消息区
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 10))
        }
        scroll.addView(list)
        root.addView(scroll)

        // 输入栏（置顶固定，不随滚动）
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 12))
        }
        val etInput = EditText(activity).apply {
            hint = "输入消息…"
            setTextColor(TEXT)
            setHintTextColor(SUB)
            textSize = 14f
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSend = MaterialButton(activity).apply {
            text = "发送"
            textSize = 13f
            setTextColor(Color.BLACK)
            setBackgroundColor(PRIMARY)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
        }
        inputRow.addView(etInput)
        inputRow.addView(btnSend)
        root.addView(inputRow)

        val dialog = android.app.Dialog(activity).apply {
            setContentView(root)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        fun render() {
            list.removeAllViews()
            val msgs = ChatStore.loadMessages(activity, peerId)
            if (msgs.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = "还没有消息，打个招呼吧"
                    setTextColor(SUB)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(activity, 40), 0, 0)
                })
            }
            msgs.forEach { m -> list.addView(bubble(activity, m)) }
            scroll.post {
                scroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        fun send() {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) return
            val myId = meId(activity)
            if (myId <= 0) {
                AppDialogs.tip(activity, "未获取到用户 ID", "请先在「我的」页刷新资料后重试")
                return
            }
            if (!ChatApi.isConfigured(activity)) {
                AppDialogs.tip(activity, "私聊服务器未配置", "请到「扩展设置」里填写私聊服务器地址")
                return
            }

            val msgId = ChatStore.newMessageId()
            val now = System.currentTimeMillis()

            // 1) 本地先落盘（pending）
            ChatStore.appendMessage(
                activity, peerId,
                ChatStore.Message(msgId, myId, peerId, text, now, outgoing = true, state = "pending")
            )
            ChatStore.setPeerNickname(activity, peerId, nickname)
            etInput.setText("")
            render()

            // 2) 加密 + 发送（后台线程）
            Thread {
                val key = ensureSessionKey(activity, peerId, myId)
                if (key == null) {
                    ChatStore.updateMessageState(activity, peerId, msgId, "failed")
                    activity.runOnUiThread {
                        AppDialogs.tip(activity, "发送失败", "对方不存在或服务器未返回公钥（HTTP 404）")
                        render()
                    }
                    return@Thread
                }
                val cipher = ChatCrypto.encrypt(key, text)
                val ok = ChatApi.send(activity, myId, peerId, msgId, cipher, now)
                ChatStore.updateMessageState(activity, peerId, msgId, if (ok) "sent" else "failed")
                activity.runOnUiThread { render() }
            }.start()
        }

        btnSend.setOnClickListener { send() }
        render()
        dialog.show()

        // 拉取新消息（页面打开期间每 8 秒）
        val poller = object : Runnable {
            override fun run() {
                Thread {
                    val got = pullMessages(activity, peerId)
                    if (got) activity.runOnUiThread { ChatStore.clearUnread(activity, peerId); render() }
                }.start()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 8000)
            }
        }
        dialog.setOnDismissListener {
            android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(poller)
        }
        dialog.setOnShowListener {
            root.translationY = 80f
            root.alpha = 0f
            root.animate().translationY(0f).alpha(1f).setDuration(200).start()
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post(poller)
    }

    private fun bubble(activity: AppCompatActivity, m: ChatStore.Message): View {
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (m.outgoing) Gravity.END else Gravity.START
            setPadding(0, dp(activity, 3), 0, dp(activity, 3))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (m.outgoing) BUBBLE_OUT else BUBBLE_IN)
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (m.outgoing) marginStart = dp(activity, 48) else marginEnd = dp(activity, 48) }
        }
        box.addView(TextView(activity).apply {
            text = m.text
            setTextColor(TEXT)
            textSize = 14f
        })
        val stateText = when (m.state) {
            "pending" -> "发送中…"
            "sent" -> "已发送"
            "failed" -> "发送失败"
            else -> ""
        }
        if (m.outgoing && stateText.isNotEmpty()) {
            box.addView(TextView(activity).apply {
                text = stateText
                setTextColor(if (m.state == "failed") 0xFFE57373.toInt() else SUB)
                textSize = 10f
                gravity = Gravity.END
            })
        }
        wrap.addView(box)
        return wrap
    }

    // ==================== 加密/收发 ====================

    /** 确保有与对端的会话密钥（必要时向上传/拉取公钥） */
    private fun ensureSessionKey(activity: AppCompatActivity, peerId: Int, myId: Int): ByteArray? {
        ChatCrypto.getCachedKey(peerId)?.let { return it }
        ChatCrypto.init(activity)

        // 上传自己的公钥（幂等）
        ChatApi.uploadKey(activity, myId, token(activity), ChatCrypto.publicKeyBase64())

        // 拉对端公钥
        val peerPub = ChatApi.fetchPeerKey(activity, peerId)
        if (peerPub == null) {
            LogManager.w("[CHAT] 对端 $peerId 公钥不存在（404）")
            return null
        }
        val key = ChatCrypto.deriveSessionKey(peerPub)
        ChatCrypto.cacheKey(peerId, key)
        LogManager.d("[CHAT] 会话密钥指纹 ${ChatCrypto.fingerprint(key)}（对端 #$peerId）")
        return key
    }

    /** 拉取并解密新消息，返回是否有更新 */
    private fun pullMessages(activity: AppCompatActivity, peerId: Int): Boolean {
        if (!ChatApi.isConfigured(activity)) return false
        val myId = meId(activity)
        if (myId <= 0) return false

        val list = ChatApi.pull(activity, lastPullTs(activity))
        if (list.isEmpty()) return false

        val key = ensureSessionKey(activity, peerId, myId) ?: return false
        var updated = false
        var maxTs = lastPullTs(activity)

        list.forEach { o ->
            val from = o.optInt("from")
            val to = o.optInt("to")
            if (to != myId || from != peerId) return@forEach
            val cipher = o.optString("cipher")
            val msgId = o.optString("msgId").ifBlank { ChatStore.newMessageId() }
            val ts = o.optLong("ts", System.currentTimeMillis())
            val plain = ChatCrypto.decrypt(key, cipher)
            if (plain == null) {
                LogManager.e("[CHAT] 消息 $msgId 解密失败")
                return@forEach
            }
            ChatStore.appendMessage(
                activity, peerId,
                ChatStore.Message(msgId, from, to, plain, ts, outgoing = false, state = "delivered")
            )
            updated = true
            if (ts > maxTs) maxTs = ts
        }
        if (maxTs > lastPullTs(activity)) {
            activity.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .edit().putLong("chat_last_pull", maxTs).apply()
        }
        return updated
    }

    private fun lastPullTs(activity: AppCompatActivity): Long =
        activity.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .getLong("chat_last_pull", 0L)
}