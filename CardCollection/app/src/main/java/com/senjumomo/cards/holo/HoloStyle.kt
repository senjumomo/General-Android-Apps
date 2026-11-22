package com.senjumomo.cards.holo

/** Which foil effect a rarity gets, and where the artwork sits on the card. */
object HoloStyle {
    // Keep in sync with the FOIL modes in res/raw/holo_core.agsl.
    const val COMMON = 0
    const val RARE = 1
    const val SUPER = 2
    const val ULTRA = 3
    const val SECRET = 4
    const val PRISMATIC = 5
    const val ULTIMATE = 6
    const val GHOST = 7
    const val STARLIGHT = 8
    const val COLLECTORS = 9
    const val PLATINUM = 10
    const val QUARTER_CENTURY = 11
    const val GOLD = 12
    const val PARALLEL = 13

    /** Maps a rarity name (e.g. "1st Edition Prismatic Secret Rare") to a foil mode. */
    fun mode(rarity: String): Int {
        val r = rarity.lowercase()
        return when {
            "quarter century" in r -> QUARTER_CENTURY
            "starlight" in r -> STARLIGHT
            "collector" in r -> COLLECTORS
            "ghost" in r -> GHOST
            "ultimate" in r -> ULTIMATE           // includes Prismatic Ultimate
            "platinum" in r -> PLATINUM
            "prismatic" in r -> PRISMATIC
            "secret" in r -> SECRET
            "gold" in r -> GOLD                   // Gold Rare, Premium Gold Rare
            "starfoil" in r || "mosaic" in r || "shatterfoil" in r || "parallel" in r -> PARALLEL
            "ultra" in r -> ULTRA
            "super" in r -> SUPER
            "rare" in r -> RARE
            else -> COMMON
        }
    }

    fun label(mode: Int) = when (mode) {
        RARE -> "Silver foil name"
        SUPER -> "Holo artwork"
        ULTRA -> "Gold foil name · holo artwork"
        SECRET -> "Secret Rare foil pattern"
        PRISMATIC -> "Prismatic foil"
        ULTIMATE -> "Embossed relief"
        GHOST -> "Ghost foil"
        STARLIGHT -> "Starlight foil · whole card"
        COLLECTORS -> "Collector's foil · whole card"
        PLATINUM -> "Platinum foil pattern"
        QUARTER_CENTURY -> "Quarter Century foil"
        GOLD -> "Gold foil"
        PARALLEL -> "Full-card foil"
        else -> "Gloss finish"
    }

    /** Artwork box as fractions of the card (x0, y0, x1, y1). Pendulum artwork is wider and shorter. */
    fun artRect(pendulum: Boolean): FloatArray =
        if (pendulum) floatArrayOf(0.067f, 0.178f, 0.933f, 0.629f)
        else floatArrayOf(0.119f, 0.180f, 0.881f, 0.705f)
}
