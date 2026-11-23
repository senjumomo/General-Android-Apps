package com.senjumomo.cards.holo

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Holographic card drawn with OpenGL ES 2.0 (available on every Android phone). The card tilts in
 * 3D with the gyroscope ([externalTilt]) and finger drags, and every pixel is shaded by the shared
 * foil shader (res/raw/holo_core.agsl) according to the card's rarity.
 */
class HoloGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    /** Gyroscope tilt (x, y in -1..1), read every frame. Null = gentle idle sway. */
    @Volatile var externalTilt: (() -> Pair<Float, Float>)? = null

    /** Called on the main thread if OpenGL can't be used (with the reason, for bug reports). */
    var onError: ((String) -> Unit)? = null

    private val renderer = Renderer()

    // Finger drag adds to the sensor tilt and springs back when released.
    @Volatile private var dragX = 0f
    @Volatile private var dragY = 0f
    @Volatile private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startDragX = 0f
    private var startDragY = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true) // transparent around the card, showing the screen behind it
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setCard(bitmap: Bitmap, rarity: String, isPhoto: Boolean, pendulum: Boolean) {
        val bmp = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val target = 59f / 86f
        val crop = if (bmp.width.toFloat() / bmp.height > target) {
            val cw = bmp.height * target
            floatArrayOf((bmp.width - cw) / 2f / bmp.width, 0f, cw / bmp.width, 1f)
        } else {
            val ch = bmp.width / target
            floatArrayOf(0f, (bmp.height - ch) / 2f / bmp.height, 1f, ch / bmp.height)
        }
        renderer.card = CardData(
            bmp, crop, HoloStyle.mode(rarity).toFloat(), if (isPhoto) 1f else 0f, HoloStyle.artRect(pendulum),
            texelX = 1f / (bmp.width * crop[2]), texelY = 1f / (bmp.height * crop[3]),
        )
        contentDescription = "Card with ${HoloStyle.label(HoloStyle.mode(rarity)).lowercase()}. Tilt your phone or drag to see the foil."
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downX = event.x; downY = event.y
                startDragX = dragX; startDragY = dragY
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val size = min(width, height) * 0.5f
                dragX = (startDragX + (event.x - downX) / size).coerceIn(-1.2f, 1.2f)
                dragY = (startDragY + (event.y - downY) / size).coerceIn(-1.2f, 1.2f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private class CardData(
        val bitmap: Bitmap, val crop: FloatArray, val mode: Float, val photo: Float, val art: FloatArray,
        val texelX: Float, val texelY: Float,
    )

    private fun fail(message: String) {
        Log.e("HoloGLView", message)
        Handler(Looper.getMainLooper()).post { onError?.invoke(message) }
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        @Volatile var card: CardData? = null
        private var uploaded: CardData? = null

        private var cardProgram = 0
        private var shadowProgram = 0
        private var texture = 0
        private var aspect = 1f
        private val start = SystemClock.uptimeMillis()
        private var tiltX = 0f
        private var tiltY = 0f
        private var failed = false

        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)); position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            failed = false
            uploaded = null
            val core = HoloShaders.core(context)
            cardProgram = program(HoloShaders.VERTEX, HoloShaders.fragment(core))
            shadowProgram = program(HoloShaders.VERTEX, HoloShaders.SHADOW)
            if (cardProgram == 0 || shadowProgram == 0) failed = true
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspect = width.toFloat() / height
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val c = card ?: return
            if (failed) return
            if (uploaded !== c) upload(c)
            updateTilt()

            // Card size: fill the view, keeping card proportions, with room to tilt.
            // (A bit under the full height so the shadow below has room when the card tilts.)
            var h = 1.72f
            var w = h * 59f / 86f
            if (w > aspect * 1.72f) { w = aspect * 1.72f; h = w * 86f / 59f }
            val angleX = -tiltY * 0.30f   // ~17°
            val angleY = tiltX * 0.30f

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            // Shadow: slightly larger, lower, and shifted away from the light.
            draw(shadowProgram, w, h, angleX, angleY, -tiltX * 0.05f, -0.06f - tiltY * 0.04f, 1.04f)

            GLES20.glUseProgram(cardProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            uniform(cardProgram, "uImage") { GLES20.glUniform1i(it, 0) }
            uniform(cardProgram, "uSrc") { GLES20.glUniform4f(it, c.crop[0], c.crop[1], c.crop[2], c.crop[3]) }
            uniform(cardProgram, "texel") { GLES20.glUniform2f(it, c.texelX, c.texelY) }
            uniform(cardProgram, "tilt") { GLES20.glUniform2f(it, tiltX, tiltY) }
            uniform(cardProgram, "time") { GLES20.glUniform1f(it, (SystemClock.uptimeMillis() - start) / 1000f) }
            uniform(cardProgram, "mode") { GLES20.glUniform1f(it, c.mode) }
            uniform(cardProgram, "photo") { GLES20.glUniform1f(it, c.photo) }
            uniform(cardProgram, "artRect") { GLES20.glUniform4f(it, c.art[0], c.art[1], c.art[2], c.art[3]) }
            uniform(cardProgram, "resolution") { GLES20.glUniform2f(it, width.toFloat(), height.toFloat()) }
            draw(cardProgram, w, h, angleX, angleY, 0f, 0f, 1f)
        }

        private fun updateTilt() {
            val (sx, sy) = externalTilt?.invoke() ?: run {
                val t = (SystemClock.uptimeMillis() - start) / 1000f
                (sin(t * 0.7f) * 0.35f) to (cos(t * 0.5f) * 0.2f)
            }
            if (!dragging) {
                dragX *= 0.88f; dragY *= 0.88f
                if (abs(dragX) < 0.001f) dragX = 0f
                if (abs(dragY) < 0.001f) dragY = 0f
            }
            tiltX += ((sx + dragX).coerceIn(-1f, 1f) - tiltX) * 0.3f
            tiltY += ((sy + dragY).coerceIn(-1f, 1f) - tiltY) * 0.3f
        }

        private fun draw(prog: Int, w: Float, h: Float, ax: Float, ay: Float, ox: Float, oy: Float, scale: Float) {
            GLES20.glUseProgram(prog)
            uniform(prog, "uCardSize") { GLES20.glUniform2f(it, w, h) }
            uniform(prog, "uAngles") { GLES20.glUniform2f(it, ax, ay) }
            uniform(prog, "uOffset") { GLES20.glUniform2f(it, ox, oy) }
            uniform(prog, "uScale") { GLES20.glUniform1f(it, scale) }
            uniform(prog, "uAspect") { GLES20.glUniform1f(it, aspect) }
            uniform(prog, "uViewSize") { GLES20.glUniform2f(it, width.toFloat(), height.toFloat()) }
            val loc = GLES20.glGetAttribLocation(prog, "aCorner")
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(loc)
        }

        private inline fun uniform(prog: Int, name: String, set: (Int) -> Unit) {
            val loc = GLES20.glGetUniformLocation(prog, name)
            if (loc >= 0) set(loc) // the compiler may drop unused uniforms
        }

        private fun upload(c: CardData) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, c.bitmap, 0)
            uploaded = c
        }

        private fun program(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            if (vs == 0 || fs == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                fail("Shader link failed: ${GLES20.glGetProgramInfoLog(p)}")
                return 0
            }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                fail("Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}")
                return 0
            }
            return s
        }
    }
}
