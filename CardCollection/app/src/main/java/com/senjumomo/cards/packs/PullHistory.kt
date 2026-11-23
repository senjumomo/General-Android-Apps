package com.senjumomo.cards.packs

import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.random.Random

/** What an average pack of a set holds, found by opening lots of simulated packs. */
data class PackExpectation(val valueUsd: Double, val ultra: Double, val secret: Double) {
    companion object {
        fun of(cards: List<SetCard>, packs: Int = 400, random: Random = Random(1)): PackExpectation {
            if (!PackGenerator.canOpen(cards)) return PackExpectation(0.0, 0.0, 0.0)
            var value = 0.0
            var ultra = 0
            var secret = 0
            repeat(packs) {
                PackGenerator.open(cards, random).forEach { c ->
                    value += c.priceUsd ?: 0.0
                    if (c.tier >= Tier.ULTRA) ultra++
                    if (c.tier >= Tier.SECRET) secret++
                }
            }
            return PackExpectation(value / packs, ultra.toDouble() / packs, secret.toDouble() / packs)
        }
    }
}

/** Everything the Packs tab shows about past openings. */
data class PullSummary(
    val packs: Int,
    val valueCents: Long,
    val expectedValueCents: Long,
    val currency: String,
    val ultra: Int,
    val expectedUltra: Double,
    val secret: Int,
    val expectedSecret: Double,
    /** Most valuable pulls first (rarer first on equal value). */
    val best: List<Pull>,
    /** Distinct cards (set codes) pulled, per set name (lower case). */
    val progress: Map<String, Int>,
    /** Sets you've opened (name, code), most recent first. */
    val recentSets: List<Pair<String, String>>,
) {
    /**
     * How lucky you've been compared with what the odds say, as (ratio, explanation). Uses the rarest
     * level you'd expect at least one of by now; null until there's enough to say anything.
     */
    fun luck(): Pair<Double, String>? {
        if (packs < 3) return null
        return when {
            expectedSecret >= 1.0 -> secret / expectedSecret to
                "$secret Secret Rare or better from $packs packs (${fmt(expectedSecret)} expected)"
            expectedUltra >= 1.0 -> ultra / expectedUltra to
                "$ultra Ultra Rare or better from $packs packs (${fmt(expectedUltra)} expected)"
            expectedValueCents > 0 -> valueCents.toDouble() / expectedValueCents to
                "Pulled value vs the average for these packs"
            else -> null
        }
    }

    companion object {
        val EMPTY = PullSummary(0, 0, 0, "ZAR", 0, 0.0, 0, 0.0, emptyList(), emptyMap(), emptyList())

        fun of(openings: List<PackOpening>, pulls: List<Pull>, bestCount: Int = 12): PullSummary {
            if (openings.isEmpty()) return EMPTY
            return PullSummary(
                packs = openings.size,
                valueCents = openings.sumOf { it.valueCents ?: 0L },
                expectedValueCents = openings.sumOf { it.expectedValueCents ?: 0L },
                currency = openings.maxBy { it.openedAt }.currency,
                ultra = pulls.count { it.tier >= Tier.ULTRA.ordinal },
                expectedUltra = openings.sumOf { it.expectedUltra },
                secret = pulls.count { it.tier >= Tier.SECRET.ordinal },
                expectedSecret = openings.sumOf { it.expectedSecret },
                best = pulls.filter { it.tier >= Tier.SUPER.ordinal || (it.valueCents ?: 0L) > 0 }
                    .sortedWith(compareByDescending<Pull> { it.valueCents ?: 0L }.thenByDescending { it.tier })
                    .distinctBy { it.code + "|" + it.rarity }
                    .take(bestCount),
                progress = pulls.groupBy { it.setName.lowercase() }.mapValues { (_, p) -> p.map { it.code.uppercase() }.distinct().size },
                recentSets = openings.sortedByDescending { it.openedAt }.distinctBy { it.setName.lowercase() }
                    .map { it.setName to it.setCode },
            )
        }

        private fun fmt(d: Double) = if (d >= 10) d.roundToLong().toString() else "%.1f".format(d)
    }
}

/** Saves openings off the main thread, outliving the screen that opened the pack. */
object PullRecorder {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun record(dao: PullDao, opening: PackOpening, pulls: List<Pull>) {
        scope.launch { runCatching { dao.record(opening, pulls) } }
    }
}
