package www.xdyl.hygge.com

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * 圆形头像：有图显示图，没图或加载失败时显示首字母。
 *
 * 尺寸由调用方的 layoutParams 决定（44dp / 32dp 都可以）。
 */
class AvatarView(context: Context) : FrameLayout(context) {

    private val iv = ImageView(context)
    private val tv = TextView(context)

    init {
        addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        tv.gravity = Gravity.CENTER
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun bind(
        name: String?,
        avatar: String?,
        textSizeSp: Float = 16f,
        bgColor: Int = 0xFF3A3A3A.toInt(),
        textColor: Int = 0xFFFFFFFF.toInt()
    ) {
        val letter = (name?.trim().orEmpty().take(1)).ifBlank { "?" }.uppercase()
        tv.text = letter
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        tv.setTextColor(textColor)
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
        }
        iv.visibility = GONE
        tv.visibility = VISIBLE

        AvatarLoader.load(iv, avatar) { ok ->
            iv.visibility = if (ok) VISIBLE else GONE
            tv.visibility = if (ok) GONE else VISIBLE
        }
    }
}
