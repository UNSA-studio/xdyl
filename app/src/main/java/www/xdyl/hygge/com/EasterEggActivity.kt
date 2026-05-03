package www.xdyl.hygge.com

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.Random

class EasterEggActivity : AppCompatActivity() {
    private val random = Random()
    private lateinit var prefs: SharedPreferences
    private var csvBrowseDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easter_egg)
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)

        val content = findViewById<LinearLayout>(R.id.contentLayout)
        setupFallingViews(content)

        // 线程数上限
        val editThreadLimit = findViewById<TextInputEditText>(R.id.etThreadLimit)
        editThreadLimit.setText(prefs.getInt("thread_limit", 256).toString())
        editThreadLimit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull()?.coerceIn(128, 1024) ?: 256
                prefs.edit().putInt("thread_limit", value).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // NeoForge 检查开关
        val switchNeoforge = findViewById<SwitchMaterial>(R.id.swNeoforgeCheck)
        switchNeoforge.isChecked = prefs.getBoolean("neoforge_check_enabled", false)
        switchNeoforge.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("neoforge_check_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "NeoForge 检查已开启" else "NeoForge 检查已关闭", Toast.LENGTH_SHORT).show()
        }

        // 清理孤儿文件开关
        val switchClean = findViewById<SwitchMaterial>(R.id.swCleanOrphanFiles)
        switchClean.isChecked = prefs.getBoolean("clean_orphan_files", true)
        switchClean.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("clean_orphan_files", isChecked).apply()
        }

        // 本地 CSV 开关及选择按钮
        val switchLocalCsv = findViewById<SwitchMaterial>(R.id.swLocalCsv)
        val btnPickCsv = findViewById<MaterialButton>(R.id.btnPickCsv)
        switchLocalCsv.isChecked = prefs.getBoolean("use_local_csv", false)
        btnPickCsv.visibility = if (switchLocalCsv.isChecked) View.VISIBLE else View.GONE
        switchLocalCsv.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_local_csv", isChecked).apply()
            btnPickCsv.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                prefs.edit().remove("local_csv_path").apply()
            }
        }
        btnPickCsv.setOnClickListener { showCsvFilePicker() }

        // 自定义下载地址（暂无）
        findViewById<MaterialButton>(R.id.btnDownloadUrl).setOnClickListener {
            Toast.makeText(this, "自定义下载地址暂未实现", Toast.LENGTH_SHORT).show()
        }
        // 成就（暂无）
        findViewById<MaterialButton>(R.id.btnAchievements).setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }
    }

    // ---------- 内置 CSV 文件选择器 ----------
    private fun showCsvFilePicker() {
        val lastPath = prefs.getString("csv_browser_last_path", Environment.getExternalStorageDirectory().absolutePath)
        val startDir = File(lastPath)
        if (!startDir.exists()) startDir.mkdirs()

        val view = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        val tvPath = view.findViewById<TextView>(R.id.tvPath)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)
        recycler.layoutManager = LinearLayoutManager(this)

        var currentDir = startDir
        tvPath.text = currentDir.absolutePath

        fun loadDir(dir: File) {
            val files = dir.listFiles()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                    tv.setBackgroundColor(0xFF1E1E1E.toInt())
                    tv.setTextColor(0xFFFFFFFF.toInt())
                    return object : RecyclerView.ViewHolder(tv) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val f = files[position]
                    val tv = holder.itemView as TextView
                    tv.text = f.name
                    holder.itemView.setOnClickListener {
                        if (f.isDirectory) {
                            currentDir = f
                            prefs.edit().putString("csv_browser_last_path", f.absolutePath).apply()
                            loadDir(f)
                            tvPath.text = f.absolutePath
                            updateUpButton(csvBrowseDialog!!, currentDir)
                        } else if (f.name.endsWith(".csv")) {
                            prefs.edit().putString("local_csv_path", f.absolutePath).apply()
                            csvBrowseDialog?.dismiss()
                            Toast.makeText(this@EasterEggActivity, "已选择 CSV: ${f.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@EasterEggActivity, "请选择 .csv 文件", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun getItemCount(): Int = files.size
            }
            recycler.adapter = adapter
            updateUpButton(csvBrowseDialog!!, dir)
        }
        loadDir(currentDir)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("返回上级", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val parent = currentDir.parentFile ?: return@setOnClickListener
                currentDir = parent
                prefs.edit().putString("csv_browser_last_path", parent.absolutePath).apply()
                loadDir(parent)
                tvPath.text = parent.absolutePath
            }
        }
        csvBrowseDialog = dialog
        dialog.show()
    }

    private fun updateUpButton(dialog: AlertDialog, dir: File) {
        val root = Environment.getExternalStorageDirectory()
        val btn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        if (dir.absolutePath == root.absolutePath) {
            btn.isEnabled = false
            btn.alpha = 0.5f
        } else {
            btn.isEnabled = true
            btn.alpha = 1.0f
        }
    }

    // ---------- 掉落动画 ----------
    private fun setupFallingViews(parent: View) {
        if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                child.isClickable = true
                child.setOnClickListener { performFallAnimation(it) }
                setupFallingViews(child)
            }
        }
    }

    private fun performFallAnimation(view: View) {
        val targetY = (view.parent as? ViewGroup)?.height?.toFloat() ?: return
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener { ani ->
            val fraction = ani.animatedFraction
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
