package com.tungsten.fcl.nebula

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tungsten.fcl.R
import java.security.MessageDigest

/**
 * 扩展页（原彩蛋页）：线程解锁 / NeoForge 检查 / 孤儿文件清理 / 隐藏终端入口。
 * 隐藏终端入口仅在 ADB 广播解锁后可见。
 */
class NebulaEasterEggActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("nebula_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nebula_egg)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        val swUnlock = findViewById<SwitchMaterial>(R.id.swUnlockThread)
        swUnlock.isChecked = prefs.getBoolean("unlock_thread_limit", false)
        swUnlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("unlock_thread_limit", isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "线程上限已解锁至 1024" else "线程上限已锁定为 128",
                Toast.LENGTH_SHORT
            ).show()
        }

        val swNeoforge = findViewById<SwitchMaterial>(R.id.swNeoforgeCheck)
        swNeoforge.isChecked = prefs.getBoolean("neoforge_check_enabled", true)
        swNeoforge.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("neoforge_check_enabled", isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "NeoForge 检查已开启" else "NeoForge 检查已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }

        val swClean = findViewById<SwitchMaterial>(R.id.swCleanOrphanFiles)
        swClean.isChecked = prefs.getBoolean("clean_orphan_files", true)
        swClean.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("clean_orphan_files", isChecked).apply()
        }

        // 隐藏终端：仅 ADB 广播解锁后显示入口
        val btnTerminal = findViewById<MaterialButton>(R.id.btnTerminal)
        btnTerminal.visibility = if (isTerminalUnlocked()) View.VISIBLE else View.GONE
        btnTerminal.setOnClickListener {
            startActivity(Intent(this, NebulaTerminalActivity::class.java))
        }

        // 模组白名单
        findViewById<MaterialButton>(R.id.btnWhitelist).setOnClickListener { showWhitelistDialog() }
    }

    /** 模组白名单（与旧版交互一致：多选删除 / 输入添加 / 循环刷新） */
    private fun showWhitelistDialog() {
        val whitelist = (prefs.getStringSet("mod_whitelist", emptySet()) ?: emptySet()).toMutableList()
        val items = whitelist.toTypedArray()
        val checked = BooleanArray(items.size)

        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("模组白名单")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("添加") { d, _ ->
                d.dismiss()
                val input = EditText(this)
                input.hint = "输入模组文件名"
                MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                    .setTitle("添加白名单")
                    .setView(input)
                    .setPositiveButton("确定") { d2, _ ->
                        d2.dismiss()
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty() && !whitelist.contains(name)) {
                            whitelist.add(name)
                            saveWhitelist(whitelist)
                            Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show()
                        }
                        // 添加后回到白名单（刷新列表）
                        showWhitelistDialog()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        // 取消也回到白名单
                        showWhitelistDialog()
                    }
                    .setOnCancelListener {
                        showWhitelistDialog()
                    }
                    .show()
            }
            .setNegativeButton("删除选中") { d, _ ->
                val toRemove = mutableListOf<String>()
                for (i in items.indices) {
                    if (checked[i]) toRemove.add(items[i])
                }
                if (toRemove.isNotEmpty()) {
                    whitelist.removeAll(toRemove)
                    saveWhitelist(whitelist)
                    Toast.makeText(this, "已删除 ${toRemove.size} 项", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未选中任何项", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
                // 删除后回到白名单（刷新列表）
                showWhitelistDialog()
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun saveWhitelist(list: List<String>) {
        prefs.edit().putStringSet("mod_whitelist", list.toSet()).apply()
    }

    /** 校验解锁状态：terminal_enabled 且签名匹配当前包名 */
    private fun isTerminalUnlocked(): Boolean {
        if (!prefs.getBoolean("terminal_enabled", false)) return false
        val savedSig = prefs.getString("terminal_sig", "") ?: ""
        return savedSig == sha256(salt2() + packageName)
    }

    private fun salt2(): String = listOf(
        "s", "t", "a", "r", "_", "x", "d", "y", "l"
    ).joinToString("")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}