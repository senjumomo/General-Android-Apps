package com.senjumomo.cards.packs

import com.senjumomo.cards.data.CardLookup
import com.senjumomo.cards.holo.HoloStyle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A booster set from YGOPRODeck's set list. */
data class CardSet(
    val code: String,
    val name: String,
    val cardCount: Int,
    val date: String,
    val imageUrl: String?,
)

/** One card as printed in a set. */
data class SetCard(
    val name: String,
    val code: String,
    val rarity: String,
    val artworkId: Long,
    val priceUsd: Double?,
    val type: String,
) {
    val tier get() = Tier.of(rarity)
}

/** How exciting a pull is: drives the order cards are revealed in and the glow before the flip. */
enum class Tier(val label: String) {
    COMMON("Common"), RARE("Rare"), SUPER("Super Rare"), ULTRA("Ultra Rare"), SECRET("Secret"), PREMIUM("Premium");

    companion object {
        fun of(rarity: String): Tier = when (HoloStyle.mode(rarity)) {
            HoloStyle.COMMON -> COMMON
            HoloStyle.RARE -> RARE
            HoloStyle.SUPER, HoloStyle.PARALLEL -> SUPER
            HoloStyle.ULTRA, HoloStyle.ULTIMATE, HoloStyle.GOLD -> ULTRA
            HoloStyle.SECRET, HoloStyle.PRISMATIC, HoloStyle.PLATINUM, HoloStyle.GHOST -> SECRET
            else -> PREMIUM // Starlight, Collector's, Quarter Century
        }
    }
}

object PackParsing {
    private val excluded = listOf(
        "structure deck", "starter deck", "speed duel", "promo", "prize", "tournament", "sneak peek",
        "participation", "championship", "starter set", "deck:", "demo", "token", "jumbo", "duelist league",
    )

    /** Booster-like sets only (enough cards, not decks/promos), newest first. */
    fun parseSets(json: String): List<CardSet> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val name = o.optString("set_name")
            val count = o.optInt("num_of_cards")
            if (count < 30 || excluded.any { name.lowercase().contains(it) }) return@mapNotNull null
            CardSet(
                code = o.optString("set_code"),
                name = name,
                cardCount = count,
                date = o.optString("tcg_date"),
                imageUrl = o.optString("set_image").takeIf { it.startsWith("http") },
            )
        }.distinctBy { it.name }.sortedByDescending { it.date }
    }

    /** Cards printed in [setName], one entry per printing (a card can appear in several rarities). */
    fun parseSetCards(json: String, setName: String): List<SetCard> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<SetCard>()
        for (i in 0 until data.length()) {
            val c = data.getJSONObject(i)
            val artwork = c.optJSONArray("card_images")?.optJSONObject(0)?.optLong("id") ?: c.getLong("id")
            val cardPrice = c.optJSONArray("card_prices")?.optJSONObject(0)?.optString("tcgplayer_price")
                ?.toDoubleOrNull()?.takeIf { it > 0 }
            val sets = c.optJSONArray("card_sets") ?: continue
            for (j in 0 until sets.length()) {
                val s = sets.getJSONObject(j)
                if (!s.optString("set_name").equals(setName, ignoreCase = true)) continue
                out += SetCard(
                    name = c.getString("name"),
                    code = s.optString("set_code"),
                    rarity = s.optString("set_rarity"),
                    artworkId = artwork,
                    priceUsd = s.optString("set_price").toDoubleOrNull()?.takeIf { it > 0 } ?: cardPrice,
                    type = c.optString("type"),
                )
            }
        }
        return out.distinctBy { it.code + "|" + it.rarity }
    }
}

/**
 * Makes booster packs with approximate pull rates. Konami doesn't publish exact odds; these follow
 * commonly cited community figures for 9-card packs:
 *  - Modern core sets (no Rares): 7 Commons, 1 Super Rare, 1 "foil slot".
 *  - Older core sets (with Rares): 7 Commons, 1 Rare, 1 foil slot.
 *  - All-foil sets (no Commons, e.g. Rarity Collections): every slot is a foil slot.
 * In the foil slot, rarer cards replace the Super Rare at the rates in [specialOdds].
 */
object PackGenerator {
    const val PACK_SIZE = 9

    /** Chance per foil slot, by base rarity (edition prefix removed). */
    val specialOdds = linkedMapOf(
        "Ultra Rare" to 1.0 / 6,
        "Gold Rare" to 1.0 / 6,
        "Secret Rare" to 1.0 / 12,
        "Ultimate Rare" to 1.0 / 12,
        "Platinum Secret Rare" to 1.0 / 24,
        "Prismatic Secret Rare" to 1.0 / 24,
        "Quarter Century Secret Rare" to 1.0 / 24,
        "Collector's Rare" to 1.0 / 48,
        "Ghost Rare" to 1.0 / 288,
        "Starlight Rare" to 1.0 / 288,
    )

    private fun base(r: String) = CardLookup.baseRarity(r)

    fun canOpen(cards: List<SetCard>) = cards.distinctBy { it.code }.size >= PACK_SIZE

    fun open(cards: List<SetCard>, random: Random = Random.Default): List<SetCard> {
        val byRarity = cards.groupBy { base(it.rarity) }
        val commons = byRarity["Common"].orEmpty() + byRarity["Short Print"].orEmpty()
        val rares = byRarity["Rare"].orEmpty()
        val supers = byRarity["Super Rare"].orEmpty()
        val pack = mutableListOf<SetCard>()
        fun pickFrom(list: List<SetCard>) = list.filter { c -> pack.none { it.code == c.code } }
            .ifEmpty { list }.random(random)

        if (commons.size >= 7) {
            repeat(7) { pack += pickFrom(commons) }
            when {
                rares.isNotEmpty() -> pack += pickFrom(rares)
                supers.isNotEmpty() -> pack += pickFrom(supers)
                else -> pack += foilSlot(byRarity, random, pack)
            }
            pack += foilSlot(byRarity, random, pack)
        } else {
            // All-foil set: fill what commons there are, then foil slots.
            commons.shuffled(random).take(PACK_SIZE / 2).forEach { pack += it }
            while (pack.size < PACK_SIZE) pack += foilSlot(byRarity, random, pack)
        }
        // Least exciting first, the big pull last.
        return pack.withIndex().sortedWith(compareBy({ it.value.tier.ordinal }, { it.index })).map { it.value }
    }

    private fun foilSlot(byRarity: Map<String, List<SetCard>>, random: Random, pack: List<SetCard>): SetCard {
        val present = specialOdds.filterKeys { byRarity[it]?.isNotEmpty() == true }
        val baseline = listOf("Super Rare", "Rare", "Common").firstOrNull { byRarity[it]?.isNotEmpty() == true }
        val roll = random.nextDouble()
        var acc = 0.0
        val scale = if (baseline == null && present.isNotEmpty()) 1.0 / present.values.sum() else 1.0
        var chosen: String? = null
        for ((rarity, p) in present) {
            acc += p * scale
            if (roll < acc) { chosen = rarity; break }
        }
        val pool = byRarity[chosen ?: baseline ?: byRarity.keys.first()].orEmpty()
        return pool.filter { c -> pack.none { it.code == c.code } }.ifEmpty { pool }.random(random)
    }
}
