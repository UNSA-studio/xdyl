package www.xdyl.hygge.com

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
        editThreadLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = editThreadLimit.text.toString().toIntOrNull()?.coerceIn(128, 1024) ?: 256
                prefs.edit().putInt("thread_limit", value).apply()
                Toast.makeText(this, "Thread limit set to $value", Toast.LENGTH_SHORT).show()
            }
        }

        val switchNeoforge = findViewById<SwitchMaterial>(R.id.swNeoforgeCheck)
        switchNeoforge.isChecked = prefs.getBoolean("neoforge_check_enabled", false)
        switchNeoforge.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("neoforge_check_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "NeoForge check enabled" else "NeoForge check disabled", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAchievements).setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }
        findViewById<Button>(R.id.btnDownloadUrl).setOnClickListener {
            Toast.makeText(this, "Custom download URL not implemented", Toast.LENGTH_SHORT).show()
        }
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
