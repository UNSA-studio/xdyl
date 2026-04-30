package www.xdyl.hygge.com

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Random

class EasterEggActivity : AppCompatActivity() {
    private val random = Random()
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easter_egg)
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)

        val content = findViewById<LinearLayout>(R.id.contentLayout)
        setupFallingViews(content)

        findViewById<Button>(R.id.btnAchievements).setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }

        findViewById<TextView>(R.id.tvThreadLimit).setOnClickListener {
            Toast.makeText(this, "线程上限已提升至 512！", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnDownloadUrl).setOnClickListener {
            Toast.makeText(this, "自定义下载地址功能暂未实现", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCheckNeoforge).setOnClickListener {
            checkNeoforgeVersion()
        }
    }

    private fun checkNeoforgeVersion() {
        val targetVersion = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
        val launcherRoot = prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath)
        val mc = findMinecraftDir(File(launcherRoot)) ?: run {
            showAlert("错误", "未找到 .minecraft 文件夹")
            return
        }
        val versionsDir = File(mc, "versions")
        val versionDir = File(versionsDir, targetVersion)
        if (!versionDir.exists()) {
            showAlert("错误", "版本文件夹不存在: $targetVersion")
            return
        }

        val neoforgeJars = versionDir.listFiles()?.filter { it.nameWithoutExtension.startsWith("neoforge-", true) } ?: emptyList()
        if (neoforgeJars.isEmpty()) {
            showAlert("错误", "未找到 Neoforge 驱动 jar")
            return
        }

        val versionPattern = Regex("neoforge-(\\d+\\.\\d+\\.\\d+)")
        var installedVersion = ""
        for (jar in neoforgeJars) {
            val match = versionPattern.find(jar.name)
            if (match != null) {
                installedVersion = match.groupValues[1]
                break
            }
        }
        if (installedVersion.isEmpty()) {
            showAlert("错误", "无法从文件名解析 Neoforge 版本")
            return
        }

        val required = "21.1.227"
        if (compareVersion(installedVersion, required) < 0) {
            showAlert("版本过低", "当前 Neoforge 版本: $installedVersion\n需要 $required 或更高版本\n请更新后再试")
            prefs.edit().putBoolean("neoforge_verified", false).apply()
        } else {
            showAlert("版本正常", "Neoforge 版本: $installedVersion\n满足要求！")
            prefs.edit().putBoolean("neoforge_verified", true).apply()
        }
    }

    private fun findMinecraftDir(start: File): File? {
        val mc = File(start, ".minecraft")
        if (mc.exists()) return mc
        val mcAlt = File(start, "minecraft")
        return if (mcAlt.exists()) mcAlt else null
    }

    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }

    private fun showAlert(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun setupFallingViews(parent: View) {
        if (parent is android.view.ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                child.isClickable = true
                child.setOnClickListener { view ->
                    performFallAnimation(view)
                }
                setupFallingViews(child)
            }
        }
    }

    private fun performFallAnimation(view: View) {
        val parent = view.parent as? FrameLayout ?: return
        val targetY = parent.height.toFloat()

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            view.translationY = fraction * targetY
            view.rotation = fraction * 360f * random.nextFloat()
            view.alpha = 1f - fraction * 0.8f
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                view.translationY = 0f
                view.rotation = 0f
                view.alpha = 1f
            }
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })
        animator.duration = 800
        animator.start()
    }
}
