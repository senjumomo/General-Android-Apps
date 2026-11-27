package com.senjumomo.utilities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.math.BigDecimal
import java.math.RoundingMode

/** Small shared helpers for animation and touch feedback. */
object Motion {
    /**
     * Animations only run on a visible screen and when the system animations aren't switched off
     * (also keeps the screenshot tests deterministic: their views are never attached).
     */
    fun enabled(view: View): Boolean {
        if (!view.isAttachedToWindow) return false
        val scale = Settings.Global.getFloat(
            view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        return scale > 0f
    }

    fun dp(view: View, value: Float) = value * view.resources.displayMetrics.density

    /** A light tick, e.g. for the − / + buttons and for moving between chart bars. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A firmer "that worked" buzz for copy / share / save. */
    fun confirm(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /** Fades [views] in while sliding them up a little, one after another. */
    fun riseIn(views: List<View>, firstDelay: Long = 0L, step: Long = 55L) {
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            if (!enabled(v)) {
                v.alpha = 1f
                v.translationY = 0f
                return@forEachIndexed
            }
            v.alpha = 0f
            v.translationY = dp(v, 22f)
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(firstDelay + i * step)
                .setDuration(420)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /** A quick "pop" on [view], used when a month is saved. */
    fun pulse(view: View, scale: Float = 1.07f) {
        if (!enabled(view)) return
        view.pivotX = 0f
        view.pivotY = view.height / 2f
        view.animate().cancel()
        view.animate().scaleX(scale).scaleY(scale).setDuration(150).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }.start()
    }
}

/** Shows a rand amount in [view] and counts up or down to it when it changes. */
class AmountText(private val view: TextView, private val format: (BigDecimal) -> String = Bill::money) {
    private var displayed: BigDecimal? = null
    private var animator: ValueAnimator? = null

    /** Shows "—" (or any text) instead of an amount; the next [set] won't count from the old number. */
    fun clear(text: String) {
        animator?.cancel()
        displayed = null
        view.text = text
    }

    fun set(target: BigDecimal, animate: Boolean = true) {
        animator?.cancel()
        val from = displayed
        if (!animate || from == null || from.compareTo(target) == 0 || !Motion.enabled(view)) {
            displayed = target
            view.text = format(target)
            return
        }
        val delta = target - from
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                // Whole rands while counting; the exact amount once it lands.
                val progress = BigDecimal((it.animatedValue as Float).toDouble())
                val now = (from + delta.multiply(progress)).setScale(0, RoundingMode.HALF_UP)
                displayed = now
                view.text = format(now)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    displayed = target
                    view.text = format(target)
                }
            })
            start()
        }
    }
}
