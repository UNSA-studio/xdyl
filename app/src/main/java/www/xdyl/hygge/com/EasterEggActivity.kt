package www.xdyl.hygge.com

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
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

        val switchNeoforge = findViewById<SwitchMaterial>(R.id.swNeoforgeCheck)
        switchNeoforge.isChecked = prefs.getBoolean("neoforge_check_enabled", false)
        switchNeoforge.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("neoforge_check_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "NeoForge 检查已开启" else "NeoForge 检查已关闭", Toast.LENGTH_SHORT).show()
        }

        val switchClean = findViewById<SwitchMaterial>(R.id.swCleanOrphanFiles)
        switchClean.isChecked = prefs.getBoolean("clean_orphan_files", true)
        switchClean.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("clean_orphan_files", isChecked).apply()
        }

        findViewById<Button>(R.id.btnAchievements).setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }
        findViewById<Button>(R.id.btnDownloadUrl).setOnClickListener {
            Toast.makeText(this, "自定义下载地址暂未实现", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFallingViews(parent: View) {
        if (parent is android.view.ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                child.isClickable = true
                child.setOnClickListener { view -> performFallAnimation(view) }
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
