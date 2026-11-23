package com.senjumomo.cards.packs

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.senjumomo.cards.R
import kotlin.random.Random

/**
 * Pack-opening sound effects (synthesised by tools/sounds/make_sounds.py, no borrowed audio).
 * Low-latency via SoundPool; respects the mute toggle, which is remembered.
 */
class PackSounds(context: Context) {
    private val prefs = context.getSharedPreferences("packs", Context.MODE_PRIVATE)
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val tear = pool.load(context, R.raw.pack_tear, 1)
    private val rip = pool.load(context, R.raw.pack_rip, 1)
    private val shimmer = pool.load(context, R.raw.pack_shimmer, 1)
    private val whoosh = pool.load(context, R.raw.pack_whoosh, 1)
    private val flip = pool.load(context, R.raw.pack_flip, 1)
    private val buildUp = pool.load(context, R.raw.pack_buildup, 1)
    private val revealBig = pool.load(context, R.raw.pack_reveal_big, 1)
    private val reveal = pool.load(context, R.raw.pack_reveal, 1)
    private val twinkle = pool.load(context, R.raw.pack_twinkle, 1)

    private var buildUpStream = 0

    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(value) { prefs.edit().putBoolean("muted", value).apply(); if (value) pool.autoPause() }

    private fun play(id: Int, volume: Float = 1f, rate: Float = 1f): Int =
        if (muted) 0 else pool.play(id, volume, volume, 1, 0, rate)

    /** A little paper crackle per step of the tear, varied so it doesn't sound looped. */
    fun tearTick() { play(tear, 0.7f, 0.85f + Random.nextFloat() * 0.3f) }
    fun rip() { play(rip); play(shimmer, 0.55f) }
    fun whoosh() { play(whoosh, 0.6f, 0.9f + Random.nextFloat() * 0.2f) }
    fun flip() { play(flip, 0.8f) }
    fun twinkle() { play(twinkle, 0.35f, 0.9f + Random.nextFloat() * 0.25f) }
    /** The riser, sped up to end with a build-up lasting [ms]. */
    fun buildUp(ms: Long) { buildUpStream = play(buildUp, 0.7f, (1300f / ms).coerceIn(0.5f, 2f)) }
    fun reveal(big: Boolean) {
        if (buildUpStream != 0) { pool.stop(buildUpStream); buildUpStream = 0 }
        if (big) play(revealBig, 0.9f) else play(reveal, 0.8f)
    }

    fun release() = pool.release()
}
