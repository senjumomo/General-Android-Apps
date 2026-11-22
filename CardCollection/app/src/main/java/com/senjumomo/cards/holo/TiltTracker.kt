package com.senjumomo.cards.holo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.PI

/**
 * Turns phone orientation into a tilt of -1..1 per axis relative to how the phone was held when
 * the viewer opened. Uses the game rotation vector (gyro + accelerometer, no compass), smooths it,
 * and slowly re-centres so a new grip doesn't leave the card stuck at an angle.
 */
class TiltTracker(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    @Suppress("DEPRECATION")
    private val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

    val available get() = sensor != null

    /** Latest tilt (x = left/right, y = forward/back), -1..1. */
    var x = 0f; private set
    var y = 0f; private set

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)
    private var basePitch = Float.NaN
    private var baseRoll = 0f

    fun start() {
        basePitch = Float.NaN
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Keep axes right if the screen is rotated.
        when (display?.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            else -> System.arraycopy(rotation, 0, remapped, 0, 9)
        }
        SensorManager.getOrientation(remapped, angles)
        val pitch = angles[1]
        val roll = angles[2]
        if (basePitch.isNaN()) { basePitch = pitch; baseRoll = roll }

        val dx = wrap(roll - baseRoll)
        val dy = wrap(pitch - basePitch)
        // Drift the reference towards the current grip (about 2% per sample).
        baseRoll += dx * 0.02f
        basePitch += dy * 0.02f

        val targetX = (dx / MAX_ANGLE).coerceIn(-1f, 1f)
        val targetY = (-dy / MAX_ANGLE).coerceIn(-1f, 1f)
        x += (targetX - x) * 0.25f
        y += (targetY - y) * 0.25f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun wrap(a: Float): Float {
        var v = a
        while (v > PI) v -= (2 * PI).toFloat()
        while (v < -PI) v += (2 * PI).toFloat()
        return v
    }

    private companion object {
        /** Tilting this far (~25°) reaches the end of the effect. */
        const val MAX_ANGLE = 0.44f
    }
}
