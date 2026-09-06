package www.xdyl.hygge.com

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.json.JSONObject
import www.xdyl.hygge.com.databinding.ActivityCommunityBinding

/**
 * 社区页：公告 / 论坛 / 排行榜 / 关于服务端。
 * 匿名端点直接可用；登录相关功能后续版本接入。
 */
class CommunityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommunityBinding
    private lateinit var api: ApiClient
    private lateinit var session: SessionStore

    private enum class Tab { ANNOUNCEMENTS, FORUM, RANK, PLAYTIME }

    private var currentTab = Tab.ANNOUNCEMENTS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommunityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionStore(this)
        api = ApiClient(session)

        binding.btnBack.setOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)

        binding.tabAnnounce.setOnClickListener { switchTab(Tab.ANNOUNCEMENTS) }
        binding.tabForum.setOnClickListener { switchTab(Tab.FORUM) }
        binding.tabRank.setOnClickListener { switchTab(Tab.RANK) }
        binding.tabPlaytime.setOnClickListener { switchTab(Tab.PLAYTIME) }

        switchTab(Tab.ANNOUNCEMENTS)
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        listOf(binding.tabAnnounce, binding.tabForum, binding.tabRank, binding.tabPlaytime).forEach {
            it.alpha = if (it.tag == tab.name) 1f else 0.5f
        }
        load(tab)
    }

    private fun load(tab: Tab) {
        binding.progress.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                when (tab) {
                    Tab.ANNOUNCEMENTS -> {
                        val root = api.get("/announcements", requiresAuth = false)
                        val items = api.extractList(root)
                        val list: List<JSONObject> = (0 until items.length()).map { idx -> items.getJSONObject(idx) }
                        render(list) { obj ->
                            val title = api.firstString(obj, "title") ?: "公告"
                            val content = api.firstString(obj, "content") ?: ""
                            val date = api.firstString(obj, "created_at") ?: ""
                            CommunityItem(title, "$date", content)
                        }
                    }
                    Tab.FORUM -> {
                        val root = api.get("/forum/posts?page=1", requiresAuth = false)
                        val items = api.extractList(root)
                        val list: List<JSONObject> = (0 until items.length()).map { idx -> items.getJSONObject(idx) }
                        render(list) { obj ->
                            val title = api.firstString(obj, "title") ?: "(无标题)"
                            val author = api.firstString(obj, "nickname", "username") ?: ""
                            val date = api.firstString(obj, "created_at") ?: ""
                            val content = api.firstString(obj, "content") ?: ""
                            val likes = obj.optInt("likes", 0)
                            CommunityItem(title, "👤 $author · $date · ❤ $likes", content)
                        }
                    }
                    Tab.RANK -> {
                        val root = api.get("/rank/coins", requiresAuth = false)
                        val items = api.extractList(root)
                        val list: List<JSONObject> = (0 until items.length()).map { idx -> items.getJSONObject(idx) }
                        render(list) { obj ->
                            val rank = obj.optInt("rank", 0)
                            val name = api.firstString(obj, "nickname", "player_name", "username") ?: ""
                            val coins = obj.optInt("coins", 0)
                            CommunityItem("#$rank  $name", "🪙 $coins 喵币", "")
                        }
                    }
                    Tab.PLAYTIME -> {
                        val root = api.get("/rank/playtime", requiresAuth = false)
                        val items = api.extractList(root)
                        val list: List<JSONObject> = (0 until items.length()).map { idx -> items.getJSONObject(idx) }
                        render(list) { obj ->
                            val rank = obj.optInt("rank", 0)
                            val name = api.firstString(obj, "player_name", "nickname") ?: ""
                            val seconds = obj.optLong("seconds", 0)
                            val h = seconds / 3600
                            val m = (seconds % 3600) / 60
                            CommunityItem("#$rank  $name", "⏱ ${h}小时${m}分", "")
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.log("[Community] 加载失败: ${e.message}")
                binding.progress.visibility = View.GONE
                binding.empty.visibility = View.VISIBLE
                binding.empty.text = "加载失败：${e.message}"
            }
        }
    }

    private data class CommunityItem(val title: String, val subtitle: String, val detail: String)

    private fun render(list: List<JSONObject>, mapper: (JSONObject) -> CommunityItem) {
        binding.progress.visibility = View.GONE
        val items = list.map(mapper)
        if (items.isEmpty()) {
            binding.empty.visibility = View.VISIBLE
            binding.empty.text = "暂无内容"
            binding.recycler.adapter = null
            return
        }
        binding.empty.visibility = View.GONE
        binding.recycler.adapter = SimpleItemAdapter(items) { item ->
            if (item.detail.isNotBlank()) {
                MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                    .setTitle(item.title)
                    .setMessage("${item.subtitle}\n\n${item.detail}")
                    .setPositiveButton("好的", null)
                    .show()
            }
        }
    }

    /** 轻量列表适配器（避免为每个Tab写布局文件） */
    private class SimpleItemAdapter(
        private val items: List<CommunityItem>,
        private val onClick: (CommunityItem) -> Unit
    ) : RecyclerView.Adapter<SimpleItemAdapter.VH>() {

        class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

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
            val title = TextView(ctx).apply {
                text = item.title
                setTextColor(0xFFA0C4FF.toInt())
                textSize = 16f
            }
            holder.row.addView(title)
            if (item.subtitle.isNotBlank()) {
                holder.row.addView(TextView(ctx).apply {
                    text = item.subtitle
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 13f
                    setPadding(0, 6, 0, 0)
                })
            }
            if (item.detail.isNotBlank()) {
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
}