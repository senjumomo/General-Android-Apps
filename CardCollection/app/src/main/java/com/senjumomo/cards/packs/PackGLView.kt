package com.senjumomo.cards.packs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import com.senjumomo.cards.holo.HoloShaders
import com.senjumomo.cards.holo.HoloStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A foil booster pack in 3D (OpenGL ES 2.0). Tilts with the gyroscope; swipe across the top to tear
 * it open, then the strip flies off and the pack drops away. Calls [onTearProgress] while tearing
 * and [onOpened] once the rip animation has finished.
 */
class PackGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    @Volatile var externalTilt: (() -> Pair<Float, Float>)? = null
    var onTearProgress: ((Float) -> Unit)? = null
    var onRip: (() -> Unit)? = null
    var onOpened: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val renderer = Renderer()

    @Volatile private var tearProgress = 0f
    @Volatile private var ripStart = 0L   // 0 = not ripped yet
    @Volatile private var dragX = 0f
    @Volatile private var dragY = 0f
    @Volatile private var dragging = false
    private var tearing = false
    private var downX = 0f
    private var downY = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** When the pack started dropping in (it falls in spinning and lands with a bounce). */
    @Volatile private var introStart = 0L

    fun setPack(bitmap: Bitmap) {
        renderer.pack = bitmap
        tearProgress = 0f
        ripStart = 0L
        introStart = SystemClock.uptimeMillis()
    }

    val isOpened get() = ripStart != 0L

    /** The tear line: centre x, y and the pack's width, in view pixels (approximate, untilted). */
    fun tearLine(): FloatArray {
        val r = packRect()
        return floatArrayOf(r[0] + r[2] / 2, r[1] + r[3] * PackArt.TEAR_Y, r[2])
    }

    /** Where the pack is, in view pixels, for touch handling (approximate, untilted). */
    private fun packRect(): FloatArray {
        val aspect = width.toFloat() / height
        var h = 1.7f
        var w = h * PackArt.WIDTH / PackArt.HEIGHT
        if (w > aspect * 1.6f) { w = aspect * 1.6f; h = w * PackArt.HEIGHT / PackArt.WIDTH }
        val pw = w / (2 * aspect) * width
        val ph = h / 2 * height
        return floatArrayOf((width - pw) / 2, (height - ph) / 2, pw, ph)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ripStart != 0L) return true
        val (left, top, pw, ph) = packRect().let { listOf(it[0], it[1], it[2], it[3]) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                // Touches on the top part of the pack tear it; anywhere else tilts it.
                tearing = event.y < top + ph * 0.3f
                dragging = !tearing
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (tearing) {
                    val p = (abs(event.x - downX) / (pw * 0.75f)).coerceIn(0f, 1f)
                    if (p > tearProgress) {
                        tearProgress = p
                        main.post { onTearProgress?.invoke(p) }
                    }
                    if (tearProgress >= 1f) rip()
                } else {
                    val size = min(width, height) * 0.5f
                    dragX = ((event.x - downX) / size).coerceIn(-1.2f, 1.2f)
                    dragY = ((event.y - downY) / size).coerceIn(-1.2f, 1.2f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                // An unfinished tear eases back.
                if (tearing && ripStart == 0L) { tearing = false; tearProgress = 0f; main.post { onTearProgress?.invoke(0f) } }
            }
        }
        return true
    }

    /** Opens the pack (also used by the accessibility "Open pack" button). */
    fun rip() {
        if (ripStart != 0L) return
        tearProgress = 1f
        ripStart = SystemClock.uptimeMillis()
        main.post { onRip?.invoke() }
        main.postDelayed({ onOpened?.invoke() }, RIP_MS)
    }

    private fun fail(message: String) {
        Log.e("PackGLView", message)
        main.post { onError?.invoke(message) }
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        @Volatile var pack: Bitmap? = null
        private var uploaded: Bitmap? = null
        private var cardProgram = 0
        private var shadowProgram = 0
        private var texture = 0
        private var aspect = 1f
        private val start = SystemClock.uptimeMillis()
        private var tiltX = 0f
        private var tiltY = 0f
        private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)); position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            uploaded = null
            cardProgram = program(HoloShaders.VERTEX, HoloShaders.fragment(HoloShaders.core(context)))
            shadowProgram = program(HoloShaders.VERTEX, HoloShaders.SHADOW)
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texture = ids[0]
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height); aspect = width.toFloat() / height
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val bmp = pack ?: return
            if (cardProgram == 0 || shadowProgram == 0) return
            if (uploaded !== bmp) upload(bmp)
            updateTilt()

            var h = 1.7f
            var w = h * PackArt.WIDTH / PackArt.HEIGHT
            if (w > aspect * 1.6f) { w = aspect * 1.6f; h = w * PackArt.HEIGHT / PackArt.WIDTH }
            val ax = -tiltY * 0.28f
            val ay = tiltX * 0.28f
            val t = if (ripStart == 0L) 0f else ((SystemClock.uptimeMillis() - ripStart) / RIP_MS.toFloat()).coerceIn(0f, 1f)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            if (t == 0f) {
                // Entrance: drops in from above with a turn, lands with a little bounce; the shadow
                // tightens as it comes down.
                val k = ((SystemClock.uptimeMillis() - introStart) / INTRO_MS.toFloat()).coerceIn(0f, 1f)
                val fall = (1f - k).let { it * it * it }
                val bounce = if (k > 0.6f) sin((k - 0.6f) / 0.4f * Math.PI.toFloat()) * 0.05f * (1f - k) * 2.5f else 0f
                val oy = fall * 2.6f + bounce
                val rz = fall * 0.6f
                val ex = ax + fall * 0.9f                                   // tipped back while falling
                val sc = 1f + fall * 0.15f
                draw(shadowProgram, w, h, ax, ay, -tiltX * 0.05f, -0.06f - tiltY * 0.04f, 1.03f + fall * 0.3f, rz, 0f, 0f, 1f - k)
                draw(cardProgram, w, h, ex, ay, 0f, oy, sc, rz, 0f, tearProgress, 0f)
            } else {
                // The rest of the pack drops away and fades...
                val drop = t * t
                draw(cardProgram, w, h, ax * (1 - t), ay * (1 - t), 0f, -drop * 2.6f, 1f, 0f, 2f, 0f,
                    ((t - 0.35f) / 0.65f).coerceIn(0f, 1f))
                // ...while the torn strip flies up and spins off.
                val e = 1f - (1f - t) * (1f - t)
                // Peels off to the side, drifting down and spinning, fading at the end.
                draw(cardProgram, w, h, ax, ay, e * 0.75f, -t * 0.25f, 1f, -e * 0.9f, 1f, 0f,
                    ((t - 0.65f) / 0.35f).coerceIn(0f, 1f))
            }
        }

        private fun updateTilt() {
            val (sx, sy) = externalTilt?.invoke() ?: run {
                val s = (SystemClock.uptimeMillis() - start) / 1000f
                (sin(s * 0.7f) * 0.3f) to (cos(s * 0.5f) * 0.18f)
            }
            if (!dragging) { dragX *= 0.88f; dragY *= 0.88f }
            tiltX += ((sx + dragX).coerceIn(-1f, 1f) - tiltX) * 0.3f
            tiltY += ((sy + dragY).coerceIn(-1f, 1f) - tiltY) * 0.3f
        }

        @Suppress("LongParameterList")
        private fun draw(
            prog: Int, w: Float, h: Float, ax: Float, ay: Float, ox: Float, oy: Float, scale: Float,
            rotZ: Float, tearMode: Float, progress: Float, fade: Float,
        ) {
            GLES20.glUseProgram(prog)
            fun u(name: String) = GLES20.glGetUniformLocation(prog, name)
            fun f1(name: String, v: Float) = u(name).let { if (it >= 0) GLES20.glUniform1f(it, v) }
            fun f2(name: String, a: Float, b: Float) = u(name).let { if (it >= 0) GLES20.glUniform2f(it, a, b) }
            fun f4(name: String, a: Float, b: Float, c: Float, d: Float) = u(name).let { if (it >= 0) GLES20.glUniform4f(it, a, b, c, d) }
            f2("uCardSize", w, h); f2("uAngles", ax, ay); f2("uOffset", ox, oy)
            f1("uScale", scale); f1("uAspect", aspect); f1("uRotZ", rotZ)
            f1("uShapeAspect", PackArt.HEIGHT.toFloat() / PackArt.WIDTH); f1("uCornerRadius", 0.012f)
            f1("uFade", fade)
            f2("uViewSize", width.toFloat(), height.toFloat())
            if (prog == cardProgram) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                u("uImage").let { if (it >= 0) GLES20.glUniform1i(it, 0) }
                f4("uSrc", 0f, 0f, 1f, 1f)
                f2("texel", 1f / PackArt.WIDTH, 1f / PackArt.HEIGHT)
                f2("tilt", tiltX, tiltY)
                f1("time", (SystemClock.uptimeMillis() - start) / 1000f)
                f1("mode", HoloStyle.PARALLEL.toFloat())
                f1("photo", 0f)
                f4("artRect", 0f, 0f, 1f, 1f)
                f1("uTearY", PackArt.TEAR_Y); f1("uTearMode", tearMode); f1("uTearProgress", progress)
                f1("uPillow", 1f)
            }
            val loc = GLES20.glGetAttribLocation(prog, "aCorner")
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(loc)
        }

        private fun upload(bmp: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            uploaded = bmp
        }

        private fun program(vertex: String, fragment: String): Int {
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
                val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) { fail("Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}"); return 0 }
                return s
            }
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            if (vs == 0 || fs == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) { fail("Shader link failed: ${GLES20.glGetProgramInfoLog(p)}"); return 0 }
            return p
        }
    }

    companion object {
        const val RIP_MS = 900L
        const val INTRO_MS = 750L
    }
}
