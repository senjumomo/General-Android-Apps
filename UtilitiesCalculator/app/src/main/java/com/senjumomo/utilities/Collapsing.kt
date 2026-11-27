package com.senjumomo.utilities

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The pure maths of the collapsing header, so it can be unit-tested. */
object Collapse {
    /**
     * The header shrinks this many px per px of finger movement. Above 1 it folds away quickly
     * instead of making you drag the whole way before the page starts to move.
     */
    const val SPEED = 2f

    /** Header height for a collapse [progress] (0 = full, 1 = compact). */
    fun height(natural: Int, compact: Int, progress: Float): Int {
        val range = max(0, natural - compact)
        return natural - (progress.coerceIn(0f, 1f) * range).roundToInt()
    }

    /**
     * New progress after the page is scrolled up by [dy] px: the header shrinks first, [speed] times
     * as fast as the finger. Returns (progress, finger px used up).
     */
    fun shrink(progress: Float, range: Int, dy: Int, speed: Float = SPEED): Pair<Float, Int> {
        if (range <= 0 || dy <= 0) return progress to 0
        val left = ((1f - progress) * range).roundToInt()
        val headerPx = min((dy * speed).roundToInt(), left)
        val used = min(dy, ceil(headerPx / speed).toInt())
        return (progress + headerPx.toFloat() / range).coerceIn(0f, 1f) to used
    }

    /** New progress after the page is pulled down by [dy] px beyond its top: the header grows back. */
    fun grow(progress: Float, range: Int, dy: Int, speed: Float = SPEED): Pair<Float, Int> {
        if (range <= 0 || dy <= 0) return progress to 0
        val room = (progress * range).roundToInt()
        val headerPx = min((dy * speed).roundToInt(), room)
        val used = min(dy, ceil(headerPx / speed).toInt())
        return (progress - headerPx.toFloat() / range).coerceIn(0f, 1f) to used
    }

    /** Where a half-open header settles when the finger lifts: whichever end is closer. */
    fun settleTarget(progress: Float): Float? = when {
        progress <= 0.01f || progress >= 0.99f -> null
        progress > 0.5f -> 1f
        else -> 0f
    }
}

/**
 * The big header content. Its height shrinks from the full header down to a thin strip as
 * [progress] goes 0 → 1: the full panel (everything not tagged "compact") fades out and is
 * clipped away from the bottom, and the strip tagged "compact" (pinned to the bottom) fades in.
 * Panels keep their natural size while shrinking, so nothing re-wraps.
 */
class CollapsingHero @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var progress = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            applyFade()
            requestLayout()
        }

    private var natural = 0
    private var compact = 0
    private var animator: ValueAnimator? = null

    /** How many px the header can shrink by. */
    val range: Int get() = max(0, natural - compact)

    fun shrinkBy(dy: Int): Int {
        val (p, used) = Collapse.shrink(progress, range, dy)
        progress = p
        return used
    }

    fun growBy(dy: Int): Int {
        val (p, used) = Collapse.grow(progress, range, dy)
        progress = p
        return used
    }

    fun animateTo(target: Float) {
        animator?.cancel()
        if (!Motion.enabled(this)) { progress = target; return }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    /** Stops an animation so a drag takes over cleanly. */
    fun stopAnimating() {
        animator?.cancel()
    }

    /** When nothing is scrolling any more, finish a half-open header off one way or the other. */
    fun settle() {
        Collapse.settleTarget(progress)?.let { animateTo(it) }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        applyFade()
    }

    private fun isCompact(child: View) = child.tag == COMPACT

    private fun applyFade() {
        // Cross-fade without overlap: the full panel is gone before the strip starts to appear.
        val full = (1f - progress * 2f).coerceIn(0f, 1f)
        val strip = ((progress - 0.5f) / 0.4f).coerceIn(0f, 1f)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.alpha = if (isCompact(child)) strip else full
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var fullH = 0
        var stripH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val inner = width - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin
            child.measure(
                MeasureSpec.makeMeasureSpec(max(0, inner), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (isCompact(child)) stripH = max(stripH, h) else fullH = max(fullH, h)
        }
        if (fullH == 0) fullH = stripH
        natural = fullH
        compact = stripH
        setMeasuredDimension(width, Collapse.height(natural, compact, progress) + paddingTop + paddingBottom)
    }

    // While it is (nearly) a thin strip nothing in it is a button; don't let faded buttons catch touches.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = progress >= 0.6f
    override fun onTouchEvent(event: MotionEvent): Boolean = progress >= 0.6f

    companion object {
        const val COMPACT = "compact"
    }
}

/** The header bar: paints [HeroDrawable] and keeps clear of the status bar. */
class HeaderBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        background = HeroDrawable(context)
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
    }
}

/**
 * The screen: header on top, the page below, bottom navigation. Scrolling the page up first
 * shrinks the header ([CollapsingHero]), then scrolls the page; pulling down at the top of the
 * page grows it back. Same idea as a collapsing app bar, but we control every pixel of it.
 */
class CollapsingRoot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), NestedScrollingParent3 {

    private val helper = NestedScrollingParentHelper(this)
    private val hero: CollapsingHero? get() = findViewById(R.id.heroArea)
    private var activeScrolls = 0

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        if (axes and ViewCompat.SCROLL_AXIS_VERTICAL == 0) return false
        hero?.stopAnimating()
        return true
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        helper.onNestedScrollAccepted(child, target, axes, type)
        activeScrolls++
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        helper.onStopNestedScroll(target, type)
        // A finger lift is followed by a fling (a second scroll) before this one stops, so only
        // settle once both are over.
        activeScrolls = max(0, activeScrolls - 1)
        if (activeScrolls == 0) hero?.settle()
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        val h = hero ?: return
        if (dy > 0) consumed[1] += h.shrinkBy(dy)
    }

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        type: Int, consumed: IntArray,
    ) {
        val h = hero ?: return
        if (dyUnconsumed < 0) consumed[1] += -h.growBy(-dyUnconsumed)
    }

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int,
    ) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))
    }

    // Platform (non-"type") versions of the same callbacks.
    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) =
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onStopNestedScroll(target: View) = onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) =
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) =
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)

    override fun getNestedScrollAxes(): Int = helper.nestedScrollAxes
}

/**
 * A LinearLayout that can be faded without being drawn into an offscreen layer first (much
 * cheaper while the header fades every frame). Fine here: the panels' pieces never overlap.
 */
class FadeLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    override fun hasOverlappingRendering(): Boolean = false
}
