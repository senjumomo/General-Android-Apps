package com.senjumomo.cards.packs

/** One card of a set in the pull binder: pulled (in its best rarity so far) or still missing. */
data class BinderEntry(
    val code: String,
    val name: String,
    val artworkId: Long,
    val pendulum: Boolean,
    /** How many copies you've pulled (any rarity). 0 = missing. */
    val count: Int,
    /** The rarest version pulled, or null if missing. */
    val bestRarity: String?,
    val bestTier: Tier?,
) {
    val pulled get() = count > 0

    companion object {
        /** Every card of the set in set-number order, with what you've pulled of each. */
        fun build(setCards: List<SetCard>, pulls: List<Pull>): List<BinderEntry> {
            val pullsByCode = pulls.groupBy { it.code.uppercase() }
            val fromSet = setCards.groupBy { it.code.uppercase() }.map { (code, printings) ->
                entry(code, printings.first().name, printings.first().artworkId,
                    printings.first().type.contains("Pendulum", true), pullsByCode[code].orEmpty())
            }
            // Pulls of codes the set list no longer has (list changed since): keep them visible.
            val known = fromSet.map { it.code }.toSet()
            val extra = pullsByCode.filterKeys { it !in known }.map { (code, p) ->
                entry(code, p.first().name, p.first().artworkId, p.first().pendulum, p)
            }
            return (fromSet + extra).sortedWith(compareBy({ number(it.code) }, { it.code }))
        }

        private fun entry(code: String, name: String, artworkId: Long, pendulum: Boolean, pulls: List<Pull>): BinderEntry {
            val best = pulls.maxByOrNull { it.tier }
            return BinderEntry(code, name, artworkId, pendulum, pulls.size, best?.rarity, best?.let { Tier.entries[it.tier] })
        }

        /** The number part of a set code ("ROTA-EN042" → 42), for ordering. */
        private fun number(code: String) = Regex("(\\d+)$").find(code)?.value?.toIntOrNull() ?: Int.MAX_VALUE
    }
}
