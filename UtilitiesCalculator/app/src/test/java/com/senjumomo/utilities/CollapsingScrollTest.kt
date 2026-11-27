package com.senjumomo.utilities

import android.app.Activity
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Drags a simulated finger up a header + scrolling page and checks the page follows the finger:
 * the header shrinks first, then the page scrolls, and no finger movement goes missing.
 * This is the test that catches the "resistance / jumpy" bug of 5.1 and 5.2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w360dp-h780dp-xxhdpi")
class CollapsingScrollTest {

    private class Rig(
        val root: CollapsingRoot,
        val hero: CollapsingHero,
        val scroll: NestedScrollView,
        val density: Float,
        val slop: Int,
    )

    private fun rig(layoutImmediately: Boolean): Rig {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val d = activity.resources.displayMetrics.density
        fun px(dp: Int) = (dp * d).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val root = CollapsingRoot(activity).apply {
            orientation = LinearLayout.VERTICAL
            this.layoutImmediately = layoutImmediately
        }
        val hero = CollapsingHero(activity).apply { id = R.id.heroArea }
        hero.addView(View(activity), FrameLayout.LayoutParams(match, px(300)))
        hero.addView(
            View(activity).apply { tag = CollapsingHero.COMPACT },
            FrameLayout.LayoutParams(match, px(58), Gravity.BOTTOM),
        )
        root.addView(hero, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = NestedScrollView(activity)
        scroll.addView(View(activity), ViewGroup.LayoutParams(match, 8000))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        activity.setContentView(root)
        controller.start().resume().visible()
        relayout(root)
        return Rig(root, hero, scroll, d, ViewConfiguration.get(activity).scaledTouchSlop)
    }

    /** What the next frame does: lay out anything that asked for it. */
    private fun relayout(root: View) {
        if (!root.isLayoutRequested && root.width > 0) return
        val w = root.rootView.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val h = root.rootView.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
    }

    /** Puts a finger down low on the page and drags it up in [steps] steps of [stepPx] each. */
    private fun dragUp(r: Rig, steps: Int, stepPx: Float) {
        val start = r.root.height * 0.8f
        val t0 = SystemClock.uptimeMillis()
        var t = t0
        fun event(action: Int, y: Float) = MotionEvent.obtain(t0, t, action, r.root.width / 2f, y, 0)
        r.root.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, start))
        relayout(r.root)
        for (i in 1..steps) {
            t += 8
            r.root.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, start - stepPx * i))
            relayout(r.root) // the frame that follows each touch event
        }
    }

    @Test fun headerShrinksThenPageScrollsWithNoFingerMovementLost() {
        val r = rig(layoutImmediately = true)
        assertEquals(0f, r.hero.progress, 0f)
        val rangePx = (300 - 58) * r.density
        val steps = 60
        val stepPx = 15f
        dragUp(r, steps, stepPx)

        // The finger moved steps * stepPx; the first touch-slop's worth is ignored by the scroller.
        val finger = steps * stepPx - r.slop
        val fingerUsedByHeader = rangePx / Collapse.SPEED
        assertEquals("header fully shrunk", 1f, r.hero.progress, 0.01f)
        val expectedScroll = finger - fingerUsedByHeader
        assertTrue(
            "page scrolled ${r.scroll.scrollY}px, expected about ${expectedScroll.toInt()}px",
            abs(r.scroll.scrollY - expectedScroll) <= 30f,
        )
    }

    @Test fun duringTheShrinkTheHeaderMovesAtTheSetSpeedWithoutLoss() {
        val r = rig(layoutImmediately = true)
        val rangePx = (300 - 58) * r.density
        val steps = 12
        val stepPx = 15f
        dragUp(r, steps, stepPx)

        val finger = steps * stepPx - r.slop
        val expectedProgress = (finger * Collapse.SPEED / rangePx).coerceAtMost(1f)
        assertEquals(expectedProgress, r.hero.progress, 0.05f)
        assertEquals("page must not scroll while the header is still shrinking", 0, r.scroll.scrollY)
    }

    @Test fun lateRelayoutLosesFingerMovement_theOldBug() {
        val r = rig(layoutImmediately = false)
        val rangePx = (300 - 58) * r.density
        val steps = 60
        val stepPx = 15f
        dragUp(r, steps, stepPx)

        val finger = steps * stepPx - r.slop
        val expectedScroll = finger - rangePx / Collapse.SPEED
        // Documenting the bug: without the immediate relayout the page ends up well off the finger.
        assertTrue(
            "expected the old behaviour to be off the finger, got ${r.scroll.scrollY} vs ${expectedScroll.toInt()}",
            abs(r.scroll.scrollY - expectedScroll) > 60f || abs(r.hero.progress - 1f) > 0.05f,
        )
    }
}
