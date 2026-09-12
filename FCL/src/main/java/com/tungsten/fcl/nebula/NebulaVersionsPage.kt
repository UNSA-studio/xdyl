package com.tungsten.fcl.nebula

import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.NebulaMainActivity
import com.tungsten.fcl.setting.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.stream.Collectors

/**
 * 版本页：列表 / 选中 / 启动（全部走 fclcore 内核数据）。
 */
class NebulaVersionsPage(
    private val activity: NebulaMainActivity,
    root: View
) {
    private val recycler: RecyclerView = root.findViewById(R.id.versionList)
    private val tvSelected: TextView = root.findViewById(R.id.tvSelectedVersion)
    private val tvEmpty: TextView = root.findViewById(R.id.tvVersionsEmpty)
    private val btnRefresh: View = root.findViewById(R.id.btnRefreshVersions)

    private val adapter = NebulaVersionAdapter(
        onLaunch = { item -> NebulaLauncher.launch(activity, item.id) },
        onSelect = { item -> select(item.id) }
    )

    private var loaded = false
    private var loading = false

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        btnRefresh.setOnClickListener { onShow(force = true) }
    }

    fun onShow(force: Boolean = false) {
        if (loading) return
        if (loaded && !force) return
        load()
    }

    private fun load() {
        loading = true
        activity.lifecycleScope.launch {
            try {
                val profile = Profiles.getSelectedProfile()
                val repository = profile.repository
                val list = withContext(Dispatchers.IO) {
                    try {
                        repository.refreshVersions()
                    } catch (e: Throwable) {
                        // 目录不存在等情况下继续尝试读取
                    }
                    repository.displayVersions.map { version ->
                        val gameVer = try {
                            repository.getGameVersion(version).orElse("")
                        } catch (e: Throwable) {
                            ""
                        }
                        val mods = try {
                            java.nio.file.Files.list(repository.getModsDirectory(version.id))
                                .use { stream -> stream.count().toInt() }
                        } catch (e: Throwable) {
                            0
                        }
                        val modpack = try {
                            repository.isModpack(version.id)
                        } catch (e: Throwable) {
                            false
                        }
                        NebulaVersionAdapter.NebulaVersionItem(version.id, gameVer, mods, modpack)
                    }.collect(Collectors.toList())
                }
                adapter.submit(list, profile.selectedVersion)
                val isEmpty = list.isEmpty()
                tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
                tvSelected.text = "当前选中：" + (profile.selectedVersion ?: "无")
                loaded = true
            } finally {
                loading = false
            }
        }
    }

    private fun select(id: String) {
        try {
            val profile = Profiles.getSelectedProfile()
            profile.selectedVersion = id
            adapter.submit(adapter.current(), id)
            tvSelected.text = "当前选中：$id"
        } catch (e: Throwable) {
            // 忽略选中失败
        }
    }
}