package com.tungsten.fcl.nebula

import android.content.Intent
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.NebulaMainActivity
import com.tungsten.fcl.setting.Accounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的页：
 * - 星灯云浪账号（论坛 / 商城 / 通知）
 * - 游戏账户（离线 / 微软，启动游戏用）
 * - 应用设置入口
 */
class NebulaProfilePage(
    private val activity: NebulaMainActivity,
    root: View
) {
    private val tvNebulaName: TextView = root.findViewById(R.id.tvNebulaName)
    private val tvNebulaStatus: TextView = root.findViewById(R.id.tvNebulaStatus)
    private val btnNebulaLogin: MaterialButton = root.findViewById(R.id.btnNebulaLogin)
    private val btnNotifications: MaterialButton = root.findViewById(R.id.btnNotifications)
    private val btnRefreshProfile: MaterialButton = root.findViewById(R.id.btnRefreshProfile)
    private val btnLogout: MaterialButton = root.findViewById(R.id.btnLogout)
    private val tvAccountName: TextView = root.findViewById(R.id.tvAccountName)
    private val tvAccountType: TextView = root.findViewById(R.id.tvAccountType)
    private val tvAboutVersion: TextView = root.findViewById(R.id.tvAboutVersion)
    private val btnSwitchAccount: MaterialButton = root.findViewById(R.id.btnSwitchAccount)
    private val btnCreateOffline: MaterialButton = root.findViewById(R.id.btnCreateOffline)
    private val btnOpenSettings: MaterialButton = root.findViewById(R.id.btnOpenSettings)

    private val session by lazy { SessionStore(activity) }
    private val api by lazy { ApiClient(session) }

    init {
        tvAboutVersion.text = "版本 " + versionName()
        btnNebulaLogin.setOnClickListener { showLoginDialog() }
        btnNotifications.setOnClickListener { showNotifications() }
        btnRefreshProfile.setOnClickListener { refreshNebulaProfile() }
        btnLogout.setOnClickListener {
            session.logout()
            refreshNebulaAccount()
            Toast.makeText(activity, "已退出登录", Toast.LENGTH_SHORT).show()
        }
        btnOpenSettings.setOnClickListener {
            activity.startActivity(Intent(activity, NebulaSettingsActivity::class.java))
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
        btnCreateOffline.setOnClickListener { showCreateOfflineDialog() }
        btnSwitchAccount.setOnClickListener { showSwitchAccountDialog() }
    }

    fun onShow() {
        refreshNebulaAccount()
        refreshGameAccount()
    }

    private fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
    } catch (e: Throwable) {
        ""
    }

    // ==================== 星灯云浪账号 ====================

    private fun refreshNebulaAccount() {
        if (session.isLoggedIn) {
            tvNebulaName.text = "星灯云浪：" + session.username
            btnNebulaLogin.visibility = View.GONE
            btnLogout.visibility = View.VISIBLE
        } else {
            tvNebulaName.text = "星灯云浪：未登录"
            tvNebulaStatus.text = "登录后可同步喵币、称号与通知"
            btnNebulaLogin.visibility = View.VISIBLE
            btnLogout.visibility = View.GONE
        }
    }

    private fun showLoginDialog() {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etUser = TextInputEditText(activity).apply { hint = "账号（邮箱或用户名）" }
        val etPass = TextInputEditText(activity).apply {
            hint = "密码"
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        col.addView(etUser)
        col.addView(etPass)
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("登录星灯云浪")
            .setView(col)
            .setPositiveButton("登录") { _, _ ->
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString()
                if (user.isBlank() || pass.isBlank()) {
                    Toast.makeText(activity, "请输入账号和密码", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.login(user, pass) }
                        refreshNebulaAccount()
                        tvNebulaStatus.text = "登录成功"
                        Toast.makeText(activity, "登录成功", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "登录失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshNebulaProfile() {
        if (!session.isLoggedIn) {
            Toast.makeText(activity, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val root = api.get("/user/profile")
                    val data = root.optJSONObject("data")
                    val nickname = data?.optString("nickname")
                        ?: data?.optString("username") ?: session.username
                    val bio = data?.optString("bio") ?: data?.optString("title") ?: ""
                    Pair(nickname, bio)
                }
                tvNebulaName.text = "星灯云浪：" + text.first
                tvNebulaStatus.text = if (text.second.isBlank()) "资料已刷新" else text.second
            } catch (e: Exception) {
                tvNebulaStatus.text = "刷新失败：" + (e.message ?: "未知错误")
            }
        }
    }

    private fun showNotifications() {
        if (!session.isLoggedIn) {
            Toast.makeText(activity, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val root = api.get("/notifications")
                    val arr = api.extractList(root)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val title = api.firstString(o, "title", "name") ?: "通知"
                        val body = api.firstString(o, "content", "message", "text", "body") ?: ""
                        val time = api.firstString(o, "created_at", "time", "date") ?: ""
                        "$title\n$body\n$time"
                    }
                }
                if (list.isEmpty()) {
                    MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
                        .setTitle("我的通知")
                        .setMessage("暂无通知")
                        .setPositiveButton("关闭", null)
                        .show()
                } else {
                    MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
                        .setTitle("我的通知")
                        .setMessage(list.joinToString("\n\n"))
                        .setPositiveButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "通知加载失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 游戏账户（启动器用） ====================

    private fun refreshGameAccount() {
        val account = try {
            Accounts.getSelectedAccount()
        } catch (e: Throwable) {
            null
        }
        if (account == null) {
            tvAccountName.text = "未登录"
            tvAccountType.text = "创建一个游戏账户以启动游戏"
        } else {
            val name = try {
                account.character ?: "账户"
            } catch (e: Throwable) {
                "账户"
            }
            val type = try {
                val factory = Accounts.getAccountFactory(account)
                Accounts.getLocalizedLoginTypeName(activity, factory)
            } catch (e: Throwable) {
                ""
            }
            tvAccountName.text = name
            tvAccountType.text = "登录方式：$type"
        }
    }

    private fun showCreateOfflineDialog() {
        val input = EditText(activity).apply {
            hint = "玩家名（英文 / 数字 / 下划线）"
            setSingleLine()
        }
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("新建离线账户")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(activity, "玩家名不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    createOffline(name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createOffline(name: String) {
        activity.lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) {
                    Accounts.FACTORY_OFFLINE.create(name, null)
                }
                Accounts.addAccount(account)
                Accounts.setSelectedAccount(account)
                refreshGameAccount()
                Toast.makeText(activity, "已创建并切换到离线账户 $name", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(activity, "创建失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSwitchAccountDialog() {
        val accounts = try {
            Accounts.getAccounts().toList()
        } catch (e: Throwable) {
            emptyList()
        }
        if (accounts.isEmpty()) {
            Toast.makeText(activity, "暂无账户，请先新建离线账户", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = accounts.map { acc ->
            val n = try {
                acc.character ?: "账户"
            } catch (e: Throwable) {
                "账户"
            }
            val t = try {
                Accounts.getLocalizedLoginTypeName(activity, Accounts.getAccountFactory(acc))
            } catch (e: Throwable) {
                ""
            }
            if (t.isBlank()) n else "$n（$t）"
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("切换账户")
            .setItems(labels) { _, which ->
                try {
                    Accounts.setSelectedAccount(accounts[which])
                    refreshGameAccount()
                } catch (e: Throwable) {
                    Toast.makeText(activity, "切换失败：" + e.message, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}