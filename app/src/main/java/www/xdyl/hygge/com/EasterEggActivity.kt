package www.xdyl.hygge.com

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Random

class EasterEggActivity : AppCompatActivity() {
    private val random = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easter_egg)

        // 使所有文字和按钮可掉落
        val root = findViewById<FrameLayout>(R.id.rootLayout)
        val content = findViewById<LinearLayout>(R.id.contentLayout)
        setupFallingViews(content)

        findViewById<Button>(R.id.btnAchievements).setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }

        findViewById<TextView>(R.id.tvThreadLimit).setOnClickListener {
            // 示例：改变线程上限（可在SharedPreferences中保存）
            Toast.makeText(this, "线程上限已提升至 512！", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnDownloadUrl).setOnClickListener {
            Toast.makeText(this, "自定义下载地址功能暂未实现", Toast.LENGTH_SHORT).show()
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
                setupFallingViews(child) // 递归处理子布局
            }
        }
    }

    private fun performFallAnimation(view: View) {
        val parent = view.parent as? FrameLayout ?: return
        val startX = view.x
        val startY = view.y
        val targetY = parent.height.toFloat()

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            view.translationY = fraction * targetY
            // 轻微旋转和透明度变化
            view.rotation = fraction * 360f * random.nextFloat()
            view.alpha = 1f - fraction * 0.8f
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                // 动画结束后重置位置并恢复透明度
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
