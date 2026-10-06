package www.xdyl.hygge.com

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * 四个内嵌页的轻量 View Holder（替代 ViewBinding 生成类）。
 * 通过 findViewById 从 include 的页面根 View 中取控件，字段名与原 binding 保持一致。
 */
object HomeHolders {

    class Home(root: View) {
        val tvTitleLine1: TextView = root.findViewById(R.id.tvTitleLine1)
        val tvTitleLine2: TextView = root.findViewById(R.id.tvTitleLine2)
        val btnSettings: ImageButton = root.findViewById(R.id.btnSettings)
        val tvPackStatus: TextView = root.findViewById(R.id.tvPackStatus)
        val btnInstallModpack: MaterialButton = root.findViewById(R.id.btnInstallModpack)
        val progressBar: LinearProgressIndicator = root.findViewById(R.id.progressBar)
        val tvStatus: TextView = root.findViewById(R.id.tvStatus)
        val logScroll: android.widget.ScrollView = root.findViewById(R.id.logScroll)
        val tvLog: TextView = root.findViewById(R.id.tvLog)
        val cardQuote: LinearLayout = root.findViewById(R.id.cardQuote)
        val tvQuoteTitle: TextView = root.findViewById(R.id.tvQuoteTitle)
        val tvQuoteChinese: TextView = root.findViewById(R.id.tvQuoteChinese)
        val tvQuoteEnglish: TextView = root.findViewById(R.id.tvQuoteEnglish)
        val tvQuoteAuthor: TextView = root.findViewById(R.id.tvQuoteAuthor)
        val tvQuoteAuthorEn: TextView = root.findViewById(R.id.tvQuoteAuthorEn)
    }

    class Community(root: View) {
        val btnBack: ImageButton = root.findViewById(R.id.btnBack)
        val tabAnnounce: TextView = root.findViewById(R.id.tabAnnounce)
        val tabForum: TextView = root.findViewById(R.id.tabForum)
        val tabRank: TextView = root.findViewById(R.id.tabRank)
        val tabPlaytime: TextView = root.findViewById(R.id.tabPlaytime)
        val communityProgress: LinearProgressIndicator = root.findViewById(R.id.communityProgress)
        val communityEmpty: TextView = root.findViewById(R.id.communityEmpty)
        val communityRecycler: RecyclerView = root.findViewById(R.id.communityRecycler)
    }

    class Shop(root: View) {
        val tvRedeemRate: TextView = root.findViewById(R.id.tvRedeemRate)
        val shopProgress: LinearProgressIndicator = root.findViewById(R.id.shopProgress)
        val shopEmpty: TextView = root.findViewById(R.id.shopEmpty)
        val shopRecycler: RecyclerView = root.findViewById(R.id.shopRecycler)
    }

    class Profile(root: View) {
        val ivAvatar: ImageView = root.findViewById(R.id.ivAvatar)
        val tvNickname: TextView = root.findViewById(R.id.tvNickname)
        val tvBio: TextView = root.findViewById(R.id.tvBio)
        val btnLogin: MaterialButton = root.findViewById(R.id.btnLogin)
        val btnRefreshProfile: MaterialButton = root.findViewById(R.id.btnRefreshProfile)
        val btnNotifications: MaterialButton = root.findViewById(R.id.btnNotifications)
        val btnOpenSettings: MaterialButton = root.findViewById(R.id.btnOpenSettings)
        val btnLogout: MaterialButton = root.findViewById(R.id.btnLogout)
        val tvProfileStatus: TextView = root.findViewById(R.id.tvProfileStatus)
    }
}