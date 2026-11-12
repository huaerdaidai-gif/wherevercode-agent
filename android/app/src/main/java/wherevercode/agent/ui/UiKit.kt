package wherevercode.agent.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small shared view helpers; colours always come from the semantic palette. */
object UiKit {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun color(context: Context, @ColorRes res: Int): Int = ContextCompat.getColor(context, res)

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        @ColorRes colorRes: Int,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color(context, colorRes))
        includeFontPadding = false
        if (bold) {
            setTypeface(typeface, Typeface.BOLD)
        }
        if (mono) {
            typeface = Typeface.MONOSPACE
        }
    }

    fun icon(
        context: Context,
        @DrawableRes res: Int,
        sizeDp: Int,
        @ColorRes tintRes: Int = 0,
    ): ImageView = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (tintRes != 0) setColorFilter(color(context, tintRes))
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    fun rounded(
        context: Context,
        @ColorRes fillRes: Int,
        radiusDp: Int,
        @ColorRes strokeRes: Int = 0,
        strokeDp: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color(context, fillRes))
        cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeRes != 0 && strokeDp > 0) {
            setStroke(dp(context, strokeDp), color(context, strokeRes))
        }
    }

    fun vertical(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    fun margin(
        view: View,
        leftDp: Int = 0,
        topDp: Int = 0,
        rightDp: Int = 0,
        bottomDp: Int = 0,
    ) {
        val context = view.context
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        params.setMargins(
            dp(context, leftDp),
            dp(context, topDp),
            dp(context, rightDp),
            dp(context, bottomDp),
        )
        view.layoutParams = params
    }

    fun timeLabel(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
    }

    fun shortTime(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
    }
}
