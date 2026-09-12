package com.tungsten.fcl.activity

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tungsten.fcl.R
import com.tungsten.fcl.nebula.NebulaCommunityPage
import com.tungsten.fcl.nebula.NebulaHomePage
import com.tungsten.fcl.nebula.NebulaProfilePage
import com.tungsten.fcl.nebula.NebulaVersionsPage

/**
 * 星云主界面（我们的 UI）——四 Tab：主页 / 版本 / 社区 / 我的。
 * 启动游戏、版本列表等能力全部走 NebulaLauncher / fclcore 内核，
 * 不再进入 FCL 原生的管理界面。
 */
class NebulaMainActivity : AppCompatActivity() {

    lateinit var homePage: NebulaHomePage
        private set
    private lateinit var versionsPage: NebulaVersionsPage
    private lateinit var communityPage: NebulaCommunityPage
    private lateinit var profilePage: NebulaProfilePage
    private lateinit var pages: List<View>
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nebula_main)

        val container = findViewById<FrameLayout>(R.id.pageContainer)
        val home = layoutInflater.inflate(R.layout.page_nebula_home, container, false)
        val versions = layoutInflater.inflate(R.layout.page_nebula_versions, container, false)
        val community = layoutInflater.inflate(R.layout.page_nebula_community, container, false)
        val profile = layoutInflater.inflate(R.layout.page_nebula_profile, container, false)
        listOf(home, versions, community, profile).forEach { container.addView(it) }
        pages = listOf(home, versions, community, profile)

        homePage = NebulaHomePage(this, home)
        versionsPage = NebulaVersionsPage(this, versions)
        communityPage = NebulaCommunityPage(this, community)
        profilePage = NebulaProfilePage(this, profile)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val index = when (item.itemId) {
                R.id.nav_home -> 0
                R.id.nav_versions -> 1
                R.id.nav_community -> 2
                R.id.nav_profile -> 3
                else -> 0
            }
            showPage(index)
            true
        }
        showPage(0)
    }

    private fun showPage(index: Int) {
        currentIndex = index
        pages.forEachIndexed { i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        when (index) {
            0 -> homePage.onShow()
            1 -> versionsPage.onShow()
            2 -> communityPage.onShow()
            3 -> profilePage.onShow()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从更新器 / 游戏返回后刷新当前页
        when (currentIndex) {
            0 -> homePage.onShow()
            1 -> versionsPage.onShow(force = true)
            3 -> profilePage.onShow()
        }
    }
}