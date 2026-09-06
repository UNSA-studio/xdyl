package www.xdyl.hygge.com

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import www.xdyl.hygge.com.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var homeBinding: HomeHolders.Home
    private lateinit var communityBinding: HomeHolders.Community
    private lateinit var shopBinding: HomeHolders.Shop
    private lateinit var profileBinding: HomeHolders.Profile
    private var communityLoaded = false
    private var shopLoaded = false
    private lateinit var session: SessionStore
    private lateinit var api: ApiClient
    private var targetModsDir: File? = null
    private var isProcessing = false
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var fileBrowserDialog: AlertDialog? = null
    private var currentBrowseDir: File = Environment.getExternalStorageDirectory()
    private var fileAdapter: FileAdapter? = null
    private var tvPath: TextView? = null
    private var tvRestrictWarning: TextView? = null
    private var recyclerView: RecyclerView? = null

    private val quoteCategories = listOf("WH", "RW", "HC", "ED", "CE", "AC")
    private val categoryNames = mapOf(
        "WH" to "警世箴言",
        "RW" to "理性思辨",
        "HC" to "心灵疗愈",
        "ED" to "存在哲思",
        "CE" to "人际纽带",
        "AC" to "行动召唤"
    )

    companion object { var instance: MainActivity? = null }

    data class Quote(val chinese: String, val english: String, val author: String, val authorEn: String, val source: String, val sourceEn: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.log("MainActivity 创建")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        instance = this
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        session = SessionStore(this)
        api = ApiClient(session)

        // 内页 binding：include 的各页
        homeBinding = HomeHolders.Home(binding.viewFlipper.getChildAt(0))
        communityBinding = HomeHolders.Community(binding.viewFlipper.getChildAt(1))
        shopBinding = HomeHolders.Shop(binding.viewFlipper.getChildAt(2))
        profileBinding = HomeHolders.Profile(binding.viewFlipper.getChildAt(3))

        // 「我的」页按钮
        profileBinding.btnLogin.setOnClickListener { showLoginDialog() }
        profileBinding.btnRefreshProfile.setOnClickListener {
            if (!session.isLoggedIn) { Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            scope.launch {
                try {
                    val root = api.get("/user/profile")
                    val data = root.getJSONObject("data")
                    profileBinding.tvNickname.text = api.firstString(data, "nickname", "username") ?: session.username
                    profileBinding.tvBio.text = api.firstString(data, "bio", "title") ?: ""
                    profileBinding.tvProfileStatus.text = "资料已刷新"
                } catch (e: Exception) {
                    profileBinding.tvProfileStatus.text = "刷新失败：" + e.message
                }
            }
        }
        profileBinding.btnNotifications.setOnClickListener { showNotifications() }
        profileBinding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        profileBinding.btnLogout.setOnClickListener {
            session.logout()
            refreshProfileUI()
            shopLoaded = false
            Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        }

        homeBinding.tvTitleLine1.text = "Nebula updater-NU"
        homeBinding.tvTitleLine2.text = "星云更新器-Android端"
        homeBinding.tvLog.movementMethod = ScrollingMovementMethod()

        // 底部导航切换
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { binding.viewFlipper.displayedChild = 0; true }
                R.id.nav_community -> { binding.viewFlipper.displayedChild = 1; ensureCommunityLoaded(); true }
                R.id.nav_shop -> { binding.viewFlipper.displayedChild = 2; ensureShopLoaded(); true }
                R.id.nav_profile -> { binding.viewFlipper.displayedChild = 3; refreshProfileUI(); true }
                else -> false
            }
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            val sw = StringWriter()
            ex.printStackTrace(PrintWriter(sw))
            LogManager.log("崩溃: ${sw.toString()}")
            defaultHandler?.uncaughtException(thread, ex)
        }

        requestStoragePermissions()
        loadDailyQuote()

        // 云端检查改为静默记录（不再弹CSV弹窗——CSV时代已终结）
        scope.launch { LogManager.log("[AUTO] 就绪，等待用户触发一键流程") }

        homeBinding.btnInstallModpack.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            // 旧独立安装入口已被一键全自动取代
            startAutoFlow()
        }
        homeBinding.btnSelectDir.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            showFileBrowser()
        }
        homeBinding.btnStartDownload.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            startAutoFlow()
        }
        // 社区入口已移至底部导航（CommunityActivity 保留供外部深链使用）
        homeBinding.btnSettings.setOnClickListener {
            it.animate().rotationBy(180f).setDuration(300).start()
            val intent = Intent(this, SettingsActivity::class.java)
            @Suppress("DEPRECATION")
            startActivity(intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("request_export_log", false)) {
            prefs.edit().putBoolean("request_export_log", false).apply()
            exportLogToFile()
        }
        loadDailyQuote()
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } else { restoreLastDirectory() }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (ContextCompat.checkSelfPermission(this, permissions[0]) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, permissions[1]) == PackageManager.PERMISSION_GRANTED) {
                restoreLastDirectory()
            } else {
                requestPermissionLauncher.launch(permissions)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) { LogManager.log("用户授予了存储权限"); restoreLastDirectory() }
        else { LogManager.log("用户拒绝了存储权限"); Toast.makeText(this, "存储权限被拒绝，部分功能不可用", Toast.LENGTH_LONG).show() }
    }

    private fun restoreLastDirectory() {
        val lastPath = prefs.getString("launcher_root", null)
        LogManager.log("restoreLastDirectory: lastPath=$lastPath")
        if (lastPath != null) {
            val dir = File(lastPath)
            if (dir.exists() && dir.isDirectory) {
                val found = findMinecraftModsDir(dir)
                if (found != null) {
                    targetModsDir = found
                    homeBinding.btnStartDownload.isEnabled = true
                    LogManager.log("成功恢复 mods 目录: ${found.absolutePath}")
                    return
                } else { LogManager.log("未能在 $lastPath 下找到 mods 目录") }
            } else { LogManager.log("上次保存的路径无效: $lastPath") }
        }
        LogManager.log("没有可恢复的目录，targetModsDir 保持为 ${targetModsDir?.absolutePath ?: "null"}")
    }

    // ========== 每日名言 ==========
    private var quoteLoading = false
    private var cachedQuote: Pair<String, Quote>? = null
    private var cachedQuoteDate: String = ""

    private fun loadDailyQuote() {
        if (quoteLoading) return
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        // 内存缓存命中，直接显示
        if (cachedQuote != null && cachedQuoteDate == today) {
            displayQuote(cachedQuote!!.first, cachedQuote!!.second)
            return
        }
        val lastDate = prefs.getString("quote_date", "")
        if (lastDate != today) {
            quoteLoading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val allQuotes = mutableListOf<Triple<String, Int, Quote>>()
                    for (cat in quoteCategories) {
                        val jsonStr = assets.open("$cat.json").bufferedReader().readText()
                        val jsonObject = JSONObject(jsonStr)
                        val quotesArray = jsonObject.getJSONArray("quotes")
                        for (i in 0 until quotesArray.length()) {
                            val obj = quotesArray.getJSONObject(i)
                            allQuotes.add(Triple(cat, i, Quote(
                                obj.getString("chinese"), obj.getString("english"),
                                obj.getString("author"), obj.getString("author_en"),
                                obj.getString("source"), obj.getString("source_en")
                            )))
                        }
                    }
                    if (allQuotes.isNotEmpty()) {
                        val (cat, idx, quote) = allQuotes[Random().nextInt(allQuotes.size)]
                        cachedQuote = Pair(cat, quote)
                        cachedQuoteDate = today
                        withContext(Dispatchers.Main) { displayQuote(cat, quote) }
                        prefs.edit().putString("quote_date", today).putString("quote_cat", cat)
                            .putInt("quote_index", idx).commit()
                    }
                } catch (e: Exception) { LogManager.log("加载名言失败: ${e.message}") }
                finally { quoteLoading = false }
            }
        } else {
            val cat = prefs.getString("quote_cat", quoteCategories[0]) ?: quoteCategories[0]
            val index = prefs.getInt("quote_index", 0)
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = assets.open("$cat.json").bufferedReader().readText()
                    val quotesArray = JSONObject(jsonStr).getJSONArray("quotes")
                    if (index in 0 until quotesArray.length()) {
                        val obj = quotesArray.getJSONObject(index)
                        val quote = Quote(
                            obj.getString("chinese"), obj.getString("english"),
                            obj.getString("author"), obj.getString("author_en"),
                            obj.getString("source"), obj.getString("source_en")
                        )
                        cachedQuote = Pair(cat, quote)
                        cachedQuoteDate = today
                        withContext(Dispatchers.Main) { displayQuote(cat, quote) }
                    } else { prefs.edit().putString("quote_date", "").apply(); loadDailyQuote() }
                } catch (e: Exception) { LogManager.log("恢复名言失败: ${e.message}"); prefs.edit().putString("quote_date", "").apply(); loadDailyQuote() }
            }
        }
    }

    // ===== 修复：固定字体大小（dp），允许换行 =====
    private fun displayQuote(category: String, quote: Quote) {
        val title = "今日名言 - ${categoryNames[category] ?: category}"

        // Force system default sans-serif font for correct CJK text metrics
        homeBinding.tvQuoteChinese.typeface = android.graphics.Typeface.DEFAULT
        homeBinding.tvQuoteEnglish.typeface = android.graphics.Typeface.DEFAULT
        homeBinding.tvQuoteAuthor.typeface = android.graphics.Typeface.DEFAULT
        homeBinding.tvQuoteAuthorEn.typeface = android.graphics.Typeface.DEFAULT

        homeBinding.tvQuoteChinese.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        homeBinding.tvQuoteEnglish.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        homeBinding.tvQuoteAuthor.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        homeBinding.tvQuoteAuthorEn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)

        homeBinding.tvQuoteTitle.text = title
        homeBinding.tvQuoteChinese.text = quote.chinese
        homeBinding.tvQuoteEnglish.text = quote.english
        homeBinding.tvQuoteAuthor.text = "- ${quote.author} / ${quote.source}"
        homeBinding.tvQuoteAuthorEn.text = "- ${quote.authorEn} / ${quote.sourceEn}"

        homeBinding.tvQuoteChinese.requestLayout()
        homeBinding.tvQuoteEnglish.requestLayout()
    }

    // ========== 文件浏览器 ==========
    private class FileAdapter(private var files: List<File>, private val onItemClick: (File) -> Unit) : RecyclerView.Adapter<FileAdapter.VH>() {
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            tv.setBackgroundColor(0xFF1E1E1E.toInt()); tv.setTextColor(0xFFFFFFFF.toInt()); return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) { holder.tv.text = files[position].name; holder.itemView.setOnClickListener { onItemClick(files[position]) } }
        override fun getItemCount() = files.size
        fun setFiles(newFiles: List<File>) { files = newFiles; notifyDataSetChanged() }
    }

    private fun showFileBrowser() {
        currentBrowseDir = File(prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath) ?: Environment.getExternalStorageDirectory().absolutePath)
        val view = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        tvPath = view.findViewById(R.id.tvPath); tvRestrictWarning = view.findViewById(R.id.tvRestrictWarning); recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView!!.layoutManager = LinearLayoutManager(this); recyclerView!!.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
        val dialog = MaterialAlertDialogBuilder(this, R.style.DialogAnimation).setView(view)
            .setPositiveButton("选择此文件夹") { _, _ -> prefs.edit().putString("launcher_root", currentBrowseDir.absolutePath).apply(); handleSelectedFolder(currentBrowseDir) }
            .setNegativeButton("返回上级", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { navigateUp() }; loadDirectory(currentBrowseDir) }
        fileBrowserDialog = dialog; dialog.show()
    }

    private fun loadDirectory(dir: File) {
        // Android 11+ 限制访问检测
        val isRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (dir.absolutePath.contains("/Android/data") || dir.absolutePath.contains("/Android/obb"))
        tvRestrictWarning?.visibility = if (isRestricted) View.VISIBLE else View.GONE
        scope.launch(Dispatchers.IO) {
            val files = dir.listFiles()?.toList()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
            withContext(Dispatchers.Main) {
                fileAdapter = FileAdapter(files) { if (it.isDirectory) navigateToDirectory(it) }
                recyclerView!!.adapter = fileAdapter; tvPath!!.text = dir.absolutePath; updateUpButtonState()
            }
        }
    }

    private fun navigateToDirectory(dir: File) {
        recyclerView!!.animate().translationX(-recyclerView!!.width.toFloat()).setDuration(250).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                currentBrowseDir = dir; loadDirectory(dir)
                recyclerView!!.translationX = recyclerView!!.width.toFloat()
                recyclerView!!.animate().translationX(0f).setDuration(250).setListener(null).start()
            }
        })
    }
    private fun navigateUp() {
        val parent = currentBrowseDir.parentFile ?: return
        recyclerView!!.animate().translationX(recyclerView!!.width.toFloat()).setDuration(250).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                currentBrowseDir = parent; loadDirectory(parent)
                recyclerView!!.translationX = -recyclerView!!.width.toFloat()
                recyclerView!!.animate().translationX(0f).setDuration(250).setListener(null).start()
            }
        })
    }

    private fun updateUpButtonState() {
        val btn = fileBrowserDialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        val isRoot = currentBrowseDir.absolutePath == Environment.getExternalStorageDirectory().absolutePath
        btn.isEnabled = !isRoot; btn.alpha = if (isRoot) 0.5f else 1.0f
    }

    private fun handleSelectedFolder(folder: File) {
        // 整合包时代：只需确认根目录下存在 .minecraft（版本目录由整合包自动创建）
        val mc = findMinecraftDir(folder)
        if (mc != null) {
            // 保存根目录即可；targetModsDir 保留用于兼容显示，指向 .minecraft
            prefs.edit().putString("launcher_root", folder.absolutePath).apply()
            targetModsDir = mc
            homeBinding.btnStartDownload.isEnabled = true
            Toast.makeText(this, "游戏目录已选择（版本将随整合包自动创建）", Toast.LENGTH_SHORT).show()
        } else {
            showError(Constants.ERROR01)
        }
        fileBrowserDialog?.dismiss()
    }

    private fun findMinecraftModsDir(launcherRoot: File): File? {
        // 兼容旧恢复逻辑：只要找得到 .minecraft 即可，mods 目录由一键流程管理
        return findMinecraftDir(launcherRoot)
    }

    private fun showError(errorCode: String) {
        LogManager.log("错误: $errorCode")
        MaterialAlertDialogBuilder(this, R.style.DialogAnimation).setTitle("意外错误!").setMessage("错误码: $errorCode\n请查看是否是您的问题,如不是,请联系开发者").setPositiveButton("确定", null).show()
    }

    private fun findMinecraftDir(start: File): File? {
        val mc = File(start, ".minecraft"); if (mc.exists()) return mc
        val mcAlt = File(start, "minecraft"); return if (mcAlt.exists()) mcAlt else null
    }

    // ==================== 社区（内嵌页） ====================

    private fun ensureCommunityLoaded() {
        if (!communityLoaded) {
            communityLoaded = true
            bindCommunityTabs()
            loadCommunityTab("ANNOUNCEMENTS")
        }
    }

    private fun bindCommunityTabs() {
        fun tab(view: TextView, key: String) {
            view.setOnClickListener {
                val tabs: List<TextView> = listOf(communityBinding.tabAnnounce, communityBinding.tabForum, communityBinding.tabRank, communityBinding.tabPlaytime)
                tabs.forEach { tb -> tb.alpha = if (tb == view) 1f else 0.5f }
                loadCommunityTab(key)
            }
        }
        tab(communityBinding.tabAnnounce, "ANNOUNCEMENTS")
        tab(communityBinding.tabForum, "FORUM")
        tab(communityBinding.tabRank, "RANK")
        tab(communityBinding.tabPlaytime, "PLAYTIME")
        communityBinding.tabAnnounce.alpha = 1f
    }

    private fun loadCommunityTab(key: String) {
        communityBinding.communityProgress.visibility = View.VISIBLE
        communityBinding.communityEmpty.visibility = View.GONE
        scope.launch {
            try {
                val requiresAuth = false
                val path = when (key) {
                    "FORUM" -> "/forum/posts?page=1"
                    "RANK" -> "/rank/coins"
                    "PLAYTIME" -> "/rank/playtime"
                    else -> "/announcements"
                }
                val root = api.get(path, requiresAuth = requiresAuth)
                val items = api.extractList(root)
                val list: List<JSONObject> = (0 until items.length()).map { idx -> items.getJSONObject(idx) }
                val rendered: List<Triple<String, String, String>> = list.map { obj ->
                    when (key) {
                        "FORUM" -> Triple(
                            api.firstString(obj, "title") ?: "(无标题)",
                            "👤 " + (api.firstString(obj, "nickname", "username") ?: "") + " · " + (api.firstString(obj, "created_at") ?: "") + " · ❤ " + obj.optInt("likes", 0),
                            api.firstString(obj, "content") ?: ""
                        )
                        "RANK" -> Triple(
                            "#" + obj.optInt("rank", 0) + "  " + (api.firstString(obj, "nickname", "player_name", "username") ?: ""),
                            "🪙 " + obj.optInt("coins", 0) + " 喵币", ""
                        )
                        "PLAYTIME" -> {
                            val seconds = obj.optLong("seconds", 0)
                            Triple(
                                "#" + obj.optInt("rank", 0) + "  " + (api.firstString(obj, "player_name", "nickname") ?: ""),
                                "⏱ " + (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分", ""
                            )
                        }
                        else -> Triple(
                            api.firstString(obj, "title") ?: "公告",
                            api.firstString(obj, "created_at") ?: "",
                            api.firstString(obj, "content") ?: ""
                        )
                    }
                }
                renderCommunity(rendered)
            } catch (e: Exception) {
                LogManager.log("[Community] 加载失败: " + e.message)
                communityBinding.communityProgress.visibility = View.GONE
                communityBinding.communityEmpty.visibility = View.VISIBLE
                communityBinding.communityEmpty.text = "加载失败：" + e.message
            }
        }
    }

    private fun renderCommunity(items: List<Triple<String, String, String>>) {
        communityBinding.communityProgress.visibility = View.GONE
        if (items.isEmpty()) {
            communityBinding.communityEmpty.visibility = View.VISIBLE
            communityBinding.communityEmpty.text = "暂无内容"
            communityBinding.communityRecycler.adapter = null
            return
        }
        communityBinding.communityEmpty.visibility = View.GONE
        val mapped: List<Pair<String, String>> = items.map { Triple(it.first, it.second, it.third); Pair(it.first, it.second) }
        communityBinding.communityRecycler.adapter = InlineItemAdapter(items.map { InlineItem(it.first, it.second, it.third) }) { item ->
            if (item.detail.isNotBlank()) {
                MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                    .setTitle(item.title)
                    .setMessage(item.subtitle + "\n\n" + item.detail)
                    .setPositiveButton("好的", null)
                    .show()
            }
        }
    }

    // ==================== 商城（内嵌页） ====================

    private fun ensureShopLoaded() {
        if (!shopLoaded) {
            shopLoaded = true
            loadRedeemRate()
            loadShopItems()
        }
    }

    private fun loadRedeemRate() {
        scope.launch {
            try {
                val root = api.get("/redeem/rate", requiresAuth = false)
                val rate = root.getJSONObject("data").optInt("rate", 10)
                shopBinding.tvRedeemRate.text = "兑换比例 1:$rate"
            } catch (e: Exception) {
                shopBinding.tvRedeemRate.text = "兑换比例未知"
            }
        }
    }

    private fun loadShopItems() {
        if (!session.isLoggedIn) {
            shopBinding.shopEmpty.visibility = View.VISIBLE
            shopBinding.shopEmpty.text = "请先在「我的」页登录"
            return
        }
        shopBinding.shopProgress.visibility = View.VISIBLE
        shopBinding.shopEmpty.visibility = View.GONE
        scope.launch {
            try {
                val root = api.get("/shop/items")
                val items = api.extractList(root)
                val list: List<JSONObject> = (0 until items.length()).map { idx -> items.getJSONObject(idx) }
                val rendered: List<InlineItem> = list.map { obj ->
                    InlineItem(
                        api.firstString(obj, "name") ?: "商品",
                        api.firstString(obj, "description") ?: "",
                        "id=" + obj.optInt("id", 0)
                    )
                }
                shopBinding.shopProgress.visibility = View.GONE
                shopBinding.shopRecycler.adapter = InlineItemAdapter(rendered) { item ->
                    scope.launch {
                        try {
                            val id = item.detail.removePrefix("id=").toInt()
                            MaterialAlertDialogBuilder(this@MainActivity, R.style.DialogAnimation)
                                .setTitle("确认购买")
                                .setMessage("购买「" + item.title + "」？\n\n" + item.subtitle)
                                .setPositiveButton("购买") { _, _ ->
                                    scope.launch {
                                        try {
                                            api.post("/shop/buy", JSONObject().put("item_id", id))
                                            Toast.makeText(this@MainActivity, "购买成功", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(this@MainActivity, "购买失败：" + e.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                shopBinding.shopProgress.visibility = View.GONE
                shopBinding.shopEmpty.visibility = View.VISIBLE
                shopBinding.shopEmpty.text = "加载失败：" + e.message
            }
        }
    }

    // ==================== 我的（登录/资料） ====================

    private fun refreshProfileUI() {
        if (session.isLoggedIn) {
            profileBinding.tvNickname.text = session.username
            profileBinding.tvBio.text = "已登录"
            profileBinding.btnLogin.visibility = View.GONE
            profileBinding.btnLogout.visibility = View.VISIBLE
        } else {
            profileBinding.tvNickname.text = "未登录"
            profileBinding.tvBio.text = "登录星灯云浪，同步你的喵币与称号"
            profileBinding.btnLogin.visibility = View.VISIBLE
            profileBinding.btnLogout.visibility = View.GONE
        }
    }

    private fun showLoginDialog() {
        val input = android.widget.LinearLayout(this)
        input.orientation = android.widget.LinearLayout.VERTICAL
        input.setPadding(48, 24, 48, 0)
        val etUser = com.google.android.material.textfield.TextInputEditText(this)
        etUser.hint = "账号（邮箱或用户名）"
        etUser.setTextColor(0xFFFFFFFF.toInt())
        val etPass = com.google.android.material.textfield.TextInputEditText(this)
        etPass.hint = "密码"
        etPass.setTextColor(0xFFFFFFFF.toInt())
        etPass.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        input.addView(etUser)
        input.addView(etPass)
        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("登录星灯云浪")
            .setView(input)
            .setPositiveButton("登录") { _, _ ->
                val account = etUser.text?.toString()?.trim() ?: ""
                val password = etPass.text?.toString() ?: ""
                if (account.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "请填写账号和密码", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                scope.launch {
                    try {
                        profileBinding.tvProfileStatus.text = "登录中..."
                        api.login(account, password)
                        refreshProfileUI()
                        profileBinding.tvProfileStatus.text = "登录成功"
                        shopLoaded = false
                        Toast.makeText(this@MainActivity, "欢迎，" + session.username, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        profileBinding.tvProfileStatus.text = "登录失败：" + e.message
                        Toast.makeText(this@MainActivity, "登录失败：" + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNotifications() {
        if (!session.isLoggedIn) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val root = api.get("/notifications")
                val items = api.extractList(root)
                val sb = StringBuilder()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    sb.append("• ").append(api.firstString(o, "title", "content") ?: "").append("\n")
                    if (sb.length > 800) { sb.append("..."); break }
                }
                MaterialAlertDialogBuilder(this@MainActivity, R.style.DialogAnimation)
                    .setTitle("我的通知")
                    .setMessage(if (sb.isBlank()) "暂无通知" else sb.toString())
                    .setPositiveButton("标记已读") { _, _ ->
                        scope.launch { runCatching { api.post("/notifications/read") } }
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "通知加载失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    data class InlineItem(val title: String, val subtitle: String, val detail: String)

    /** 通用行适配器（社区/商城共用） */
    inner class InlineItemAdapter(
        private val items: List<InlineItem>,
        private val onClick: (InlineItem) -> Unit
    ) : RecyclerView.Adapter<InlineItemAdapter.VH>() {

        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 28, 36, 28)
                setBackgroundColor(0xFF2A2A2A.toInt())
            }
            val lp = RecyclerView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 12
            row.layoutParams = lp
            return VH(row)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.row.removeAllViews()
            val ctx = holder.row.context
            holder.row.addView(TextView(ctx).apply {
                text = item.title
                setTextColor(0xFFA0C4FF.toInt())
                textSize = 16f
            })
            if (item.subtitle.isNotBlank()) {
                holder.row.addView(TextView(ctx).apply {
                    text = item.subtitle
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 13f
                    setPadding(0, 6, 0, 0)
                })
            }
            if (item.detail.isNotBlank() && !item.detail.startsWith("id=")) {
                holder.row.addView(TextView(ctx).apply {
                    text = if (item.detail.length > 80) item.detail.take(80) + "…" else item.detail
                    setTextColor(0x99FFFFFF.toInt())
                    textSize = 12f
                    setPadding(0, 6, 0, 0)
                })
            }
            holder.row.setOnClickListener { onClick(item) }
        }
    }

    // ==================== 一键全自动流程（mods.json 驱动） ====================

    /**
     * 全自动：拉 mods.json → 需要时装整合包（fclcore）→ 增量同步 new_mod/tacz → removed 清理。
     * 用户只需选过一次启动器根目录（.minecraft 所在处），其余全自动。
     */
    private fun startAutoFlow() {
        if (isProcessing) return
        val launcherRoot = prefs.getString("launcher_root", null)
        val gameRoot = launcherRoot?.let { findMinecraftDir(File(it)) }
        if (gameRoot == null || !gameRoot.exists()) {
            showError(Constants.ERROR01)
            return
        }

        isProcessing = true
        homeBinding.btnStartDownload.isEnabled = false
        homeBinding.progressBar.visibility = View.VISIBLE
        homeBinding.progressBar.progress = 0
        appendLog("[AUTO] 一键流程启动")

        scope.launch {
            try {
                // 1. 拉清单
                withContext(Dispatchers.Main) { homeBinding.tvStatus.text = "获取清单..." }
                val manifest = ManifestService().fetch()
                appendLog("[AUTO] pack_version=${manifest.packVersion}, 文件=${manifest.files.size}, 下架=${manifest.removed.size}")

                // 2. 检查整合包是否需要（重）装：pack_version 变化或版本目录缺失
                val installed = ModpackInstaller.getInstalledInfo(this@MainActivity)
                val installedVer = installed["version"]
                val installedPack = installed["pack_version"]
                val needInstall = manifest.latestModpack?.let { pack ->
                    installedVer == null || installedPack != manifest.packVersion
                } ?: false

                var versionDir: File
                if (needInstall && manifest.latestModpack != null) {
                    val pack = manifest.latestModpack!!
                    appendLog("[AUTO] 需要安装整合包: ${pack.name} (${pack.size / 1048576}MB)")
                    withContext(Dispatchers.Main) { homeBinding.tvStatus.text = "下载整合包..." }

                    val versionId = "NAST-" + manifest.packVersion.replace(Regex("[^A-Za-z0-9.\\-]"), "")
                    val zipFile = File(getExternalFilesDir(null), "modpack_${manifest.packVersion}.zip")

                    // 下载（本地已存在且哈希一致则跳过）
                    var needDownload = true
                    if (zipFile.exists() && zipFile.length() == pack.size) {
                        val installer0 = ModpackInstaller(this@MainActivity)
                        if (installer0.sha256(zipFile).equals(pack.sha256, true)) needDownload = false
                    }
                    if (needDownload) {
                        withContext(Dispatchers.IO) {
                            val client = OkHttpClient.Builder()
                                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val req = Request.Builder().url(pack.url).build()
                            client.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) throw RuntimeException("整合包下载 HTTP ${resp.code}")
                                val input = resp.body!!.byteStream()
                                val total = resp.body!!.contentLength()
                                var done = 0L
                                zipFile.outputStream().use { fos ->
                                    val buf = ByteArray(131072)
                                    var n: Int
                                    var lastPct = -1
                                    while (input.read(buf).also { n = it } != -1) {
                                        fos.write(buf, 0, n)
                                        done += n
                                        if (total > 0) {
                                            val pct = (done * 100 / total).toInt()
                                            if (pct != lastPct) {
                                                lastPct = pct
                                                withContext(Dispatchers.Main) {
                                                    homeBinding.progressBar.progress = pct / 4
                                                    homeBinding.tvStatus.text = "下载整合包 $pct%"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    appendLog("[AUTO] 整合包下载完成 (${zipFile.length() / 1048576}MB)")

                    // sha256 校验
                    withContext(Dispatchers.Main) { homeBinding.tvStatus.text = "校验整合包..." }
                    val installer = ModpackInstaller(this@MainActivity)
                    val hash = installer.sha256(zipFile)
                    if (!hash.equals(pack.sha256, true)) {
                        zipFile.delete()
                        throw RuntimeException("整合包 sha256 校验失败，已删除损坏文件，请重试")
                    }
                    appendLog("[AUTO] sha256 校验通过")

                    // 安装
                    versionDir = installer.install(zipFile, gameRoot, versionId) { p ->
                        runOnUiThread {
                            homeBinding.progressBar.progress = p.percent
                            homeBinding.tvStatus.text = p.message
                            if (p.percent % 20 == 0) appendLog("[AUTO] ${p.message}")
                        }
                    }
                    ModpackInstaller.saveInstalled(this@MainActivity, versionId, manifest.packVersion, gameRoot, versionDir)
                    zipFile.delete()
                    appendLog("[AUTO] 整合包安装完成: $versionDir")
                } else {
                    val dir = installed["version_dir"]
                    if (dir.isNullOrEmpty() || !File(dir).exists()) {
                        throw RuntimeException("未安装整合包且清单中无整合包可装")
                    }
                    versionDir = File(dir)
                    appendLog("[AUTO] 整合包已是最新 (${installedPack})，跳过安装")
                }

                // 3. 增量同步
                withContext(Dispatchers.Main) { homeBinding.tvStatus.text = "增量同步..." }
                val sync = IncrementalSync(this@MainActivity, ModpackInstaller(this@MainActivity))
                val result = sync.sync(manifest, versionDir, threadCount = 8) { p ->
                    runOnUiThread {
                        homeBinding.progressBar.progress = p.percent
                        homeBinding.tvStatus.text = p.message
                    }
                }
                result.messages.forEach { msg -> appendLog("[SYNC] " + msg) }
                appendLog("[AUTO] 同步完成: 新下 ${result.downloaded}, 已最新 ${result.skipped}, 失败 ${result.failed}, 清理 ${result.cleaned}")

                withContext(Dispatchers.Main) {
                    homeBinding.progressBar.progress = 100
                    homeBinding.tvStatus.text = if (result.failed > 0)
                        "完成（${result.failed} 个失败，详见日志）"
                    else
                        "全部完成 ✔"
                    Toast.makeText(
                        this@MainActivity,
                        if (result.failed > 0) "更新完成，但有 ${result.failed} 个文件失败" else "全部完成，可以启动游戏了",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                LogManager.log("[AUTO] 异常: ${e.message}")
                appendLog("[AUTO] 失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    homeBinding.tvStatus.text = "失败: ${e.message}"
                    Toast.makeText(this@MainActivity, "失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isProcessing = false
                withContext(Dispatchers.Main) { homeBinding.btnStartDownload.isEnabled = true }
            }
        }
    }

    fun appendLog(msg: String) { runOnUiThread { homeBinding.tvLog.text = "${homeBinding.tvLog.text}\n$msg"; homeBinding.logScroll.post { homeBinding.logScroll.fullScroll(View.FOCUS_DOWN) } } }

    private fun exportLogToFile() {
        scope.launch(Dispatchers.IO) {
            try {
                val log = LogManager.getFullLog()
                val exportDir = File(Environment.getExternalStorageDirectory(), "NebulaUpdater")
                if (!exportDir.exists()) exportDir.mkdirs()
                val file = File(exportDir, "nebula_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
                FileOutputStream(file).use { it.write(log.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "日志已导出: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() { instance = null; job.cancel(); super.onDestroy() }
}
