package www.xdyl.hygge.com

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * 【私聊页面】（Material Design 3 风格）
 *
 * 会话列表：MaterialCardView（大圆角 + 描边 + 涟漪）+ 圆形字母头像 + 未读胶囊
 * 聊天窗口：MaterialToolbar 顶栏 + 圆角气泡 + TextInputLayout 输入框 + FAB 发送
 *
 * 身份标识统一用主站 username（/player/info/{username} 的键）。
 */
object ChatPage {

    // 沿用 App 既有配色（与其它页面完全一致，不引入新的底色层）
    private val SURFACE = 0xFF1E1E1E.toInt()          // = background_dark
    private val SURFACE_CONTAINER = 0xFF1E1E1E.toInt() // 不再额外铺一层
    private val SURFACE_HIGH = 0xFF2A2A2A.toInt()      // = card_background
    private val PRIMARY = 0xFFA0C4FF.toInt()
    private val ON_PRIMARY = 0xFF0A1929.toInt()
    private val ON_SURFACE = 0xFFFFFFFF.toInt()
    private val ON_SURFACE_VARIANT = 0xFF888888.toInt()
    private val HINT_COLOR = 0xFF6B6B6B.toInt()  // 与项目其他对话框的 hint 一致
    private val OUTLINE = 0xFF3A3A3A.toInt()
    private val OUT_BUBBLE = 0xFF333333.toInt()        // 与对方气泡同底色，靠右区分即可

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    // ==================== 头像 ====================
    /** 玩家 username -> avatar 文件名 */
    private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val avatarLoading = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val avatarClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 取某玩家的头像文件名；未命中时后台请求，拿到后回调 */
    fun peerAvatar(username: String, onReady: (String) -> Unit) {
        if (username.isBlank()) return
        avatarCache[username]?.let { onReady(it); return }
        if (avatarLoading.putIfAbsent(username, true) != null) return
        Thread {
            try {
                val url = "https://login.lanternwaves.fun/player/info/" + android.net.Uri.encode(username)
                val req = okhttp3.Request.Builder().url(url).get().build()
                avatarClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val body = r.body?.string() ?: return@use
                    val data = org.json.JSONObject(body).optJSONObject("data") ?: return@use
                    val av = data.optString("avatar", "").trim()
                    if (av.isNotEmpty()) {
                        avatarCache[username] = av
                        uiHandler.post { onReady(av) }
                    }
                }
            } catch (e: Exception) {
                LogManager.w("[CHAT] 取头像失败 ${e.message}")
            } finally {
                avatarLoading.remove(username)
            }
        }.start()
    }

    // ==================== 会话列表 ====================

    private var convRefreshHandler: android.os.Handler? = null
    private var convNetTick = 0
    private val convRefreshTask = object : Runnable {
        override fun run() {
            val c = lastConvContainer
            if (c != null && c.isAttachedToWindow) {
                refreshConversationList(c)
                // 每 5 个 tick（约 20 秒）向后端同步一次：
                // 以前只在进入聊天窗口时才 pull，停在列表页永远收不到新消息。
                convNetTick++
                if (convNetTick % 8 == 0) {
                    val act = c.context as? AppCompatActivity
                    if (act != null) {
                        Thread {
                            val updated = try {
                                pullMessages(act, "")
                            } catch (e: Exception) {
                                LogManager.w("[CHAT] 列表同步失败：${e.message}")
                                false
                            }
                            if (updated) {
                                act.runOnUiThread {
                                    lastConvContainer?.let { refreshConversationList(it) }
                                }
                            }
                        }.start()
                    }
                }
            }
            convRefreshHandler?.postDelayed(this, 4000L)
        }
    }

    @Volatile
    private var lastConvContainer: LinearLayout? = null

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

    fun renderConversations(activity: AppCompatActivity, container: LinearLayout) {
        lastConvContainer = container
        container.removeAllViews()
        container.setBackgroundColor(SURFACE)

        // 顶部：标题 + 新建按钮（MD 风格）
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 12), dp(activity, 8))
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(activity).apply {
            text = "私聊"
            setTextColor(ON_SURFACE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        val server = ChatApi.serverBase(activity)
        titleCol.addView(TextView(activity).apply {
            text = if (server.isBlank()) "未配置服务器" else server
            setTextColor(if (server.isBlank()) 0xFFE57373.toInt() else ON_SURFACE_VARIANT)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        header.addView(titleCol)

        val btnNew = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_edit)
            background = circleRipple(activity, SURFACE_HIGH)
            imageTintList = android.content.res.ColorStateList.valueOf(PRIMARY)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48))
            contentDescription = "新建会话"
            setOnClickListener { showNewChatDialog(activity, container) }
        }
        header.addView(btnNew)
        container.addView(header)

        val peers = ChatStore.loadIndex(activity)
        if (peers.isEmpty()) {
            container.addView(emptyState(activity, "还没有会话\n点右上角新建，输入对方的 username"))
            return
        }
        container.tag = peers.hashCode()
        peers.forEach { peer ->
            container.addView(peerCard(activity, peer))
        }
        startConversationAutoRefresh()
    }

    private fun refreshConversationList(container: LinearLayout) {
        val peers = ChatStore.loadIndex(container.context)
        val existing = container.tag as? Int ?: -1
        if (existing == peers.hashCode()) return
        container.tag = peers.hashCode()
        // 保留 header（第 0 个），清掉后面的
        while (container.childCount > 1) container.removeViewAt(container.childCount - 1)
        if (peers.isEmpty()) {
            container.addView(emptyState(container.context, "还没有会话\n点右上角新建，输入对方的 username"))
            return
        }
        val activity = container.context as AppCompatActivity
        peers.forEach { peer ->
            container.addView(peerCard(activity, peer))
        }
    }

    private fun emptyState(c: Context, text: String): View =
        TextView(c).apply {
            this.text = text
            setTextColor(ON_SURFACE_VARIANT)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(c, 32), dp(c, 64), dp(c, 32), 0)
        }

    /** 单个会话卡片（MD 风格：圆角 16dp + 描边 + 涟漪） */
    private fun peerCard(activity: AppCompatActivity, peer: ChatStore.Peer): View {
        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(activity, 8)
                marginStart = dp(activity, 12)
                marginEnd = dp(activity, 12)
            }
            radius = dp(activity, 16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(activity, 1)
            strokeColor = OUTLINE
            setCardBackgroundColor(SURFACE_HIGH)
            rippleColor = android.content.res.ColorStateList.valueOf(0x1FFFFFFF)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ChatStore.clearUnread(activity, peer.peerKey)
                openChat(activity, peer.peerKey, peer.nickname)
            }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
        }

        // 圆形头像：优先真实图片，失败回退首字母
        val avatar = AvatarView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44))
        }
        avatar.bind(peer.nickname, avatarCache[peer.peerKey], 17f, PRIMARY, ON_PRIMARY)
        peerAvatar(peer.peerKey) { av -> avatar.bind(peer.nickname, av, 17f, PRIMARY, ON_PRIMARY) }
        row.addView(avatar)

        val mid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), 0, dp(activity, 8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mid.addView(TextView(activity).apply {
            text = peer.nickname
            setTextColor(ON_SURFACE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        mid.addView(TextView(activity).apply {
            text = peer.lastText.ifBlank { "（暂无消息）" }
            setTextColor(ON_SURFACE_VARIANT)
            textSize = 12.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(activity, 3), 0, 0)
        })
        row.addView(mid)

        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        if (peer.lastTime > 0) {
            right.addView(TextView(activity).apply {
                text = formatTime(peer.lastTime)
                setTextColor(ON_SURFACE_VARIANT)
                textSize = 10.5f
            })
        }
        if (peer.unread > 0) {
            right.addView(TextView(activity).apply {
                text = if (peer.unread > 99) "99+" else peer.unread.toString()
                setTextColor(ON_PRIMARY)
                textSize = 10.5f
                gravity = Gravity.CENTER
                minWidth = dp(activity, 20)
                setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 10).toFloat()
                    setColor(PRIMARY)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 4) }
            })
        }
        row.addView(right)

        card.addView(row)
        return card
    }

    private fun showNewChatDialog(activity: AppCompatActivity, container: LinearLayout) {
        // 与项目其他对话框保持一致：裸 EditText + hint，不用 TextInputLayout
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 0), dp(activity, 24), 0)
        }
        val etUser = EditText(activity).apply {
            hint = "对方 username（如 OvalXD_chen）"
            setTextColor(ON_SURFACE)
            setHintTextColor(HINT_COLOR)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val etName = EditText(activity).apply {
            hint = "备注名（可留空）"
            setTextColor(ON_SURFACE)
            setHintTextColor(HINT_COLOR)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        wrap.addView(etUser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        wrap.addView(etName, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 8) })
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("新建私聊")
            .setView(wrap)
            .setPositiveButton("开始") { _, _ ->
                val key = etUser.text.toString().trim()
                if (key.isBlank()) {
                    AppDialogs.tip(activity, "玩家名无效", "请输入对方的 username（如 OvalXD_chen）")
                    return@setPositiveButton
                }
                val manual = etName.text.toString().trim()
                ChatStore.setPeerNickname(activity, key, manual.ifBlank { key })
                openChat(activity, key, manual.ifBlank { key })
                Thread {
                    val info = ChatApi.fetchPlayerName("https://login.lanternwaves.fun", key)
                    if (info != null) {
                        val (name, registered) = info
                        if (registered) {
                            ChatStore.setPeerNickname(activity, key, name)
                            LogManager.i("[CHAT] 解析玩家 $key -> $name")
                        } else {
                            LogManager.w("[CHAT] 玩家 $key 未注册")
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 聊天窗口 ====================

    private fun meName(activity: AppCompatActivity): String {
        val prefs = activity.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        return prefs.getString("my_player_name", "") ?: ""
    }

    private fun token(activity: AppCompatActivity): String =
        try { SessionStore(activity).accessToken } catch (e: Exception) { "" }

    fun openChat(activity: AppCompatActivity, peerKey: String, nickname: String) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
        }

        // ---- 顶栏（MaterialToolbar 语义：返回 + 标题 + 副标题）----
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 12), dp(activity, 8))
        }
        val btnBack = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            background = circleRipple(activity, Color.TRANSPARENT)
            imageTintList = android.content.res.ColorStateList.valueOf(ON_SURFACE)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48))
            contentDescription = "返回"
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(activity, 4), 0, 0, 0)
        }
        titleCol.addView(TextView(activity).apply {
            text = nickname
            setTextColor(ON_SURFACE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleCol.addView(TextView(activity).apply {
            text = peerKey
            setTextColor(ON_SURFACE_VARIANT)
            textSize = 11f
            maxLines = 1
        })
        top.addView(btnBack)
        top.addView(titleCol)
        val btnMore = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_info)
            background = circleRipple(activity, Color.TRANSPARENT)
            imageTintList = android.content.res.ColorStateList.valueOf(ON_SURFACE_VARIANT)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48))
            contentDescription = "信息"
            setOnClickListener {
                AppDialogs.tip(
                    activity,
                    "会话信息",
                    "对端：$peerKey\n昵称：$nickname\n服务：${ChatApi.serverBase(activity)}"
                )
            }
        }
        top.addView(btnMore)
        root.addView(top)

        // 分隔线
        root.addView(View(activity).apply {
            setBackgroundColor(OUTLINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1))
        })

        // ---- 消息区 ----
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 12))
        }
        scroll.addView(list)
        root.addView(scroll)

        // ---- 输入区（圆角输入框 + 圆形发送按钮）----
        val inputBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 10))
        }
        val etInput = EditText(activity).apply {
            hint = "发送消息"
            setTextColor(ON_SURFACE)
            setHintTextColor(ON_SURFACE_VARIANT)
            textSize = 14.5f
            maxLines = 5
            minLines = 1
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 22).toFloat()
                setColor(SURFACE_HIGH)
                setStroke(dp(activity, 1), OUTLINE)
            }
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSend = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_send)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PRIMARY)
                },
                null
            )
            imageTintList = android.content.res.ColorStateList.valueOf(ON_PRIMARY)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            contentDescription = "发送"
            layoutParams = LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply {
                marginStart = dp(activity, 8)
            }
        }
        inputBar.addView(etInput)
        inputBar.addView(btnSend)
        root.addView(inputBar)

        val dialog = android.app.Dialog(activity).apply {
            setContentView(root)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        btnBack.setOnClickListener { dialog.dismiss() }

        fun render() {
            list.removeAllViews()
            val msgs = ChatStore.loadMessages(activity, peerKey)
            if (msgs.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = "还没有消息，打个招呼吧"
                    setTextColor(ON_SURFACE_VARIANT)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(activity, 48), 0, 0)
                })
            }
            var lastDay = ""
            msgs.forEach { m ->
                val day = formatDay(m.time)
                if (day != lastDay) {
                    lastDay = day
                    list.addView(TextView(activity).apply {
                        text = day
                        setTextColor(ON_SURFACE_VARIANT)
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(activity, 12), 0, dp(activity, 8))
                    })
                }
                list.addView(bubble(activity, m, nickname))
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        fun send() {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) return
            val myName = meName(activity)
            if (myName.isBlank()) {
                AppDialogs.tip(activity, "未获取到玩家名", "请先在「我的」页刷新资料后重试")
                return
            }
            if (!ChatApi.isConfigured(activity)) {
                AppDialogs.tip(activity, "私聊服务器未配置", "请到「扩展设置」里填写私聊服务器地址")
                return
            }
            val msgId = ChatStore.newMessageId()
            val now = System.currentTimeMillis()
            ChatStore.appendMessage(
                activity, peerKey,
                ChatStore.Message(msgId, myName, peerKey, text, now, outgoing = true, state = "pending")
            )
            ChatStore.setPeerNickname(activity, peerKey, nickname)
            etInput.setText("")
            render()

            Thread {
                val key = ensureSessionKey(activity, peerKey, myName)
                if (key == null) {
                    ChatStore.updateMessageState(activity, peerKey, msgId, "failed")
                    activity.runOnUiThread {
                        AppDialogs.tip(activity, "发送失败", "对方不存在或服务器未返回公钥（404）")
                        render()
                    }
                    return@Thread
                }
                val cipher = ChatCrypto.encrypt(key, text)
                val (ok, err) = ChatApi.send(activity, myName, peerKey, msgId, cipher, now)
                ChatStore.updateMessageState(activity, peerKey, msgId, if (ok) "sent" else "failed")
                activity.runOnUiThread { render() }
                if (!ok) {
                    val tip = when {
                        peerKey == myName || err == "INVALID_REQUEST" -> "不能给自己发消息"
                        err == "USER_NOT_FOUND" || err == "recipient not found" ->
                            "对方还没有上传公钥：让对方装新版并打开一次私聊页"
                        err == "NETWORK" -> "网络不通（ECH 与备用通道都失败了）"
                        else -> "发送失败：" + err
                    }
                    LogManager.w("[CHAT] 发送失败：" + err)
                    // 弹窗必须在主线程创建（这里在 Thread 里跑，之前漏了 runOnUiThread 会崩）
                    activity.runOnUiThread { AppDialogs.tip(activity, "消息未发出", tip) }
                }
            }.start()
        }

        btnSend.setOnClickListener { send() }
        render()
        dialog.show()

        // 轮询：基础 15 秒；连续失败时指数退避（最多 60 秒）
        // 背景：这条网络对高频连接会直接 RST，原来 8 秒一次 + 4 个端点等于每 2 秒建连，必然被封
        val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var pollFail = 0
        val poller = object : Runnable {
            override fun run() {
                Thread {
                    val got = pullMessages(activity, peerKey)
                    if (got) {
                        pollFail = 0
                        activity.runOnUiThread {
                            ChatStore.clearUnread(activity, peerKey); render()
                        }
                    } else {
                        pollFail++
                    }
                }.start()
                val factor = when {
                    pollFail <= 1 -> 1L
                    pollFail == 2 -> 2L
                    else -> 4L
                }
                val next = (3000L * factor).coerceAtMost(20000L)
                pollHandler.postDelayed(this, next)
            }
        }
        dialog.setOnDismissListener {
            pollHandler.removeCallbacks(poller)
        }
        dialog.setOnShowListener {
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post(poller)
    }

    /**
     * 消息气泡。
     *
     * 规格取自 Material 3 的 Chat 模式 + 主流 IM（Telegram / WhatsApp / 微信）的通用做法：
     *   - 圆角 20dp，靠自己那一侧的底部是 4dp（形成“尖角”），用官方 ShapeAppearanceModel 描述，
     *     不再手写 cornerRadii 数组（之前 8 个数的顺序写错，导致两个角同时变尖）。
     *   - 自己：右对齐、深灰底 + 1dp 描边；对方：左对齐、更暗底 + 描边，并带 32dp 圆形头像。
     *   - 时间放进气泡内右下角（11sp，alpha 0.55），发送状态跟在时间前面。
     *   - 气泡最大宽度 76%，内边距 12x8，行距 +3dp。
     */
    private fun bubble(activity: AppCompatActivity, m: ChatStore.Message, peerName: String): View {
        val out = m.outgoing

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (out) Gravity.END else Gravity.START
            setPadding(dp(activity, 12), dp(activity, 3), dp(activity, 12), dp(activity, 3))
        }

        // 对方侧头像
        if (!out) {
            val avatar = AvatarView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32)).apply {
                    topMargin = dp(activity, 2)
                    marginEnd = dp(activity, 8)
                }
            }
            avatar.bind(peerName, avatarCache[peerName], 13f)
            peerAvatar(peerName) { av -> avatar.bind(peerName, av, 13f) }
            row.addView(avatar)
        }

        // 气泡本体
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val radii = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(dp(activity, 20).toFloat())
                .setTopRightCornerSize(dp(activity, 20).toFloat())
                .setBottomLeftCornerSize(dp(activity, if (out) 20 else 4).toFloat())
                .setBottomRightCornerSize(dp(activity, if (out) 4 else 20).toFloat())
                .build()
            background = com.google.android.material.shape.MaterialShapeDrawable(radii).apply {
                fillColor = android.content.res.ColorStateList.valueOf(
                    if (out) 0xFF383838.toInt() else 0xFF242424.toInt()
                )
                strokeWidth = dp(activity, 1).toFloat()
                strokeColor = android.content.res.ColorStateList.valueOf(
                    if (out) 0xFF4A4A4A.toInt() else 0xFF333333.toInt()
                )
            }
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 7))
        }

        box.addView(TextView(activity).apply {
            setTextColor(ON_SURFACE)
            textSize = 15f
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
            // 聊天内容也支持 &3 / &a 这类 MC 颜色码
            RichText.apply(this, m.text)
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.76f).toInt()
        })

        // 时间 + 状态（气泡内右下角）
        val meta = buildString {
            if (out) {
                when (m.state) {
                    "pending" -> append("发送中 · ")
                    "failed" -> append("发送失败 · ")
                }
            }
            append(formatClock(m.time))
        }
        box.addView(TextView(activity).apply {
            text = meta
            textSize = 10.5f
            setTextColor(if (m.state == "failed") 0xFFE57373.toInt() else 0xFF9E9E9E.toInt())
            alpha = 0.9f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 2) }
        })

        row.addView(box)
        return row
    }

    // ==================== 工具 ====================

    private fun circleRipple(c: Context, color: Int): android.graphics.drawable.Drawable {
        val tv = android.util.TypedValue()
        c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return ContextCompat.getDrawable(c, tv.resourceId) ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color)
        }
    }

    private fun formatClock(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private fun formatDay(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val now = java.util.Calendar.getInstance()
        val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay) return "今天"
        val yest = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        if (cal.get(java.util.Calendar.YEAR) == yest.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == yest.get(java.util.Calendar.DAY_OF_YEAR)
        ) return "昨天"
        return String.format(
            "%d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun formatTime(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val now = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        ) return formatClock(ms)
        return "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    // ==================== 加密 / 收发 ====================

    private fun ensureSessionKey(activity: AppCompatActivity, peerKey: String, myId: String): ByteArray? {
        ChatCrypto.getCachedKey(peerKey)?.let { return it }
        ChatCrypto.init(activity)
        ChatApi.uploadKey(activity, myId, ChatCrypto.publicKeyBase64())
        val peerPub = ChatApi.fetchPeerKey(activity, peerKey)
        if (peerPub == null) {
            LogManager.w("[CHAT] 对端 $peerKey 公钥不存在（404）")
            return null
        }
        val key = ChatCrypto.deriveSessionKey(peerPub)
        ChatCrypto.cacheKey(peerKey, key)
        LogManager.d("[CHAT] 会话密钥指纹 ${ChatCrypto.fingerprint(key)}（对端 $peerKey）")
        return key
    }

    /**
     * 同步消息：按服务端 seq 游标分页拉取，一次拿到全部新消息。
     *
     * 与旧版区别：
     *   1. 旧版只拉一页、按毫秒时间过滤 → 旧消息会把新消息挤掉（漏信）。
     *   2. 新版按 seq 严格递增，has_more 时继续翻页，直到拉完。
     *   3. 收件箱 + 发件箱都同步，多设备能看到自己发出的消息。
     */
    private fun pullMessages(activity: AppCompatActivity, peerKey: String): Boolean {
        if (!ChatApi.isConfigured(activity)) return false
        val myId = meName(activity)
        if (myId.isBlank()) return false

        var cursor = lastCursor(activity)
        var updated = false
        var pages = 0
        val knownIds = mutableMapOf<String, MutableSet<String>>()

        fun alreadyHave(peer: String, id: String): Boolean {
            val set = knownIds.getOrPut(peer) {
                ChatStore.loadMessages(activity, peer).map { it.id }.toMutableSet()
            }
            if (set.contains(id)) return true
            set.add(id)
            return false
        }

        while (pages < 50) {
            val page = ChatApi.pullPage(activity, cursor) ?: break
            for (o in page.items) {
                val from = o.optString("from")
                val to = o.optString("to")
                val peer = when {
                    to == myId -> from
                    from == myId -> to
                    else -> ""
                }
                if (peer.isBlank() || peer == myId) continue

                val isMine = from == myId
                val msgId = o.optString("msgId").ifBlank { "srv-" + o.optLong("seq") }
                val ts = o.optLong("ts", System.currentTimeMillis())
                val cipher = o.optString("cipher")

                if (alreadyHave(peer, msgId)) {
                    // 已在本地：自己发的不再是 pending
                    if (isMine) ChatStore.updateMessageState(activity, peer, msgId, "sent")
                    continue
                }

                if (cipher.isBlank()) continue
                val key = ensureSessionKey(activity, peer, myId)
                if (key == null) {
                    LogManager.w("[CHAT] 缺少会话密钥，跳过 $msgId（对端 $peer）")
                    continue
                }
                val plain = ChatCrypto.decrypt(key, cipher)
                if (plain == null) {
                    LogManager.e("[CHAT] 消息 $msgId 解密失败")
                    continue
                }
                ChatStore.appendMessage(
                    activity, peer,
                    ChatStore.Message(
                        msgId, from, to, plain, ts,
                        outgoing = isMine,
                        state = if (isMine) "sent" else "delivered"
                    )
                )
                updated = true
            }

            if (page.nextCursor > cursor) {
                cursor = page.nextCursor
                saveCursor(activity, cursor)
            }
            if (!page.hasMore || page.items.isEmpty()) break
            pages++
        }
        return updated
    }

    private fun lastCursor(activity: AppCompatActivity): Long =
        activity.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .getLong("chat_last_seq", 0L)

    private fun saveCursor(activity: AppCompatActivity, seq: Long) {
        activity.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .edit().putLong("chat_last_seq", seq).apply()
    }

}