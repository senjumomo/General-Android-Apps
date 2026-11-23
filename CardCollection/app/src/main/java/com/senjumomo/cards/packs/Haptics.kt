package com.senjumomo.cards.packs

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Small vibration vocabulary for pack opening. Can be switched off (remembered). */
class Haptics(context: Context) {
    private val prefs = context.getSharedPreferences("packs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(value) { prefs.edit().putBoolean("vibrate", value).apply(); if (!value) runCatching { vibrator?.cancel() } }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun compose(vararg prims: Pair<Int, Float>, fallbackMs: Long) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        // A buzz is never worth a crash (some phones refuse effects).
        runCatching {
            if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(*prims.map { it.first }.toIntArray())) {
                val c = VibrationEffect.startComposition()
                prims.forEach { (p, s) -> c.addPrimitive(p, s) }
                v.vibrate(c.compose())
            } else {
                v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    /** Light tick while tearing. */
    fun tick() = if (Build.VERSION.SDK_INT >= 30) compose(VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.6f, fallbackMs = 5) else Unit

    /** The pack rips open. */
    fun rip() = if (Build.VERSION.SDK_INT >= 30) compose(
        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.7f,
        VibrationEffect.Composition.PRIMITIVE_THUD to 1f, fallbackMs = 60,
    ) else compose(fallbackMs = 60)

    /** A card flips. */
    fun flip() = if (Build.VERSION.SDK_INT >= 30) compose(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.5f, fallbackMs = 10) else Unit

    /** Build-up before a big reveal. */
    fun buildUp() = if (Build.VERSION.SDK_INT >= 30) compose(
        VibrationEffect.Composition.PRIMITIVE_SLOW_RISE to 0.8f, fallbackMs = 300,
    ) else compose(fallbackMs = 300)

    /** Big pull! */
    fun bigReveal() = if (Build.VERSION.SDK_INT >= 30) compose(
        VibrationEffect.Composition.PRIMITIVE_THUD to 1f,
        VibrationEffect.Composition.PRIMITIVE_SPIN to 0.8f, fallbackMs = 120,
    ) else compose(fallbackMs = 120)
}
