package com.senjumomo.cards.data

import com.senjumomo.cards.net.Exchange
import com.senjumomo.cards.net.TcgSearch
import com.senjumomo.cards.net.Tcgplayer
import com.senjumomo.cards.net.Ygoprodeck
import kotlin.math.roundToLong

/** One printing a scanned set code could be (the code is shared by all rarities of that print). */
data class Printing(
    val name: String,
    val setName: String,
    val code: String,
    /** Base rarity without edition, e.g. "Ultra Rare". */
    val rarity: String,
    val priceUsd: Double?,
    val tcgplayerId: String?,
    val imageUrl: String?,
)

/** Looks up a set code (TCGplayer first for exact printing + price, YGOPRODeck as fallback). */
object CardLookup {

    suspend fun byCode(code: String): List<Printing> {
        val tcg = runCatching { TcgSearch.byCode(code) }.getOrDefault(emptyList())
        if (tcg.isNotEmpty()) {
            return tcg.map {
                Printing(it.name, it.setName, it.number.uppercase(), baseRarity(it.rarity), it.marketPriceUsd,
                    it.productId, Tcgplayer.smallImage(it.productId))
            }.distinctBy { it.rarity }
        }
        val info = Ygoprodeck.bySetCode(code) ?: return emptyList()
        return info.printings.filter { it.setCode.equals(code, true) }.map {
            Printing(info.name, it.setName, code.uppercase(), baseRarity(it.rarity), it.priceUsd, null, info.imageSmall)
        }.distinctBy { it.rarity }
    }

    /** Printings matching what the user typed (card name or set code), best matches first. */
    suspend fun search(query: String): List<Printing> {
        val q = query.trim()
        SetCode.find(q)?.takeIf { it.length >= q.length - 1 }?.let { return byCode(it) }
        val products = TcgSearch.search(q, size = 50, fuzzy = true).filter { it.number.isNotBlank() }
        return rank(q, products.map {
            Printing(cleanName(it.name, it.number), it.setName, it.number.uppercase(), baseRarity(it.rarity),
                it.marketPriceUsd, it.productId, Tcgplayer.smallImage(it.productId))
        })
    }

    /** TCGplayer sometimes appends the code: "Ash Blossom & Joyous Spring (LAVD-ENO08)". */
    fun cleanName(name: String, number: String): String =
        name.removeSuffix(" ($number)").trim()

    /** Exact name, then names starting with the query, then names containing every word, then the rest. */
    fun rank(query: String, printings: List<Printing>): List<Printing> {
        val q = query.lowercase().trim()
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun score(p: Printing): Int {
            val n = p.name.lowercase()
            return when {
                n == q -> 0
                n.startsWith(q) -> 1
                words.all { n.contains(it) } -> 2
                else -> 3
            }
        }
        return printings.withIndex()
            .sortedWith(compareBy<IndexedValue<Printing>> { score(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    /** "Common / Short Print" -> "Common"; "1st Edition Ultra Rare" -> "Ultra Rare". */
    fun baseRarity(rarity: String): String =
        rarity.substringBefore(" / ").removePrefix("1st Edition ").removePrefix("Limited Edition ").trim()

    fun rarityLabel(base: String, firstEdition: Boolean) =
        if (firstEdition) "1st Edition $base" else base

    /** Existing card of this printing (same code and rarity, ignoring edition wording) in the given list. */
    fun findOwned(cards: List<Card>, code: String, rarity: String, wishlist: Boolean): Card? =
        cards.firstOrNull {
            it.wishlist == wishlist && it.code.equals(code, true) && it.rarity.equals(rarity, true)
        }

    /** The card to save after scanning: an extra copy of an existing one, or a new card. */
    fun toCard(
        p: Printing, firstEdition: Boolean, wishlist: Boolean, existing: List<Card>, usdRate: Double?, currency: String,
        quantity: Int = 1, condition: String = "Near Mint", paidPerUnitCents: Long? = null,
    ): Card {
        val rarity = rarityLabel(p.rarity, firstEdition)
        findOwned(existing, p.code, rarity, wishlist)?.takeIf { it.condition == condition }?.let {
            return it.copy(quantity = it.quantity + quantity, paidPerUnitCents = it.paidPerUnitCents ?: paidPerUnitCents)
        }
        return Card(
            id = java.util.UUID.randomUUID().toString().replace("-", ""),
            name = p.name,
            setName = p.setName,
            code = p.code,
            rarity = rarity,
            quantity = quantity,
            condition = condition,
            valuePerUnitCents = if (p.priceUsd != null && usdRate != null) (p.priceUsd * usdRate * 100).roundToLong() else 0,
            paidPerUnitCents = paidPerUnitCents,
            currency = currency,
            wishlist = wishlist,
            tcgplayerId = p.tcgplayerId,
            imageUrl = if (p.tcgplayerId == null) p.imageUrl else null,
            dateAdded = Dates.now(),
            priceUpdatedAt = if (p.priceUsd != null && usdRate != null) Dates.now() else null,
        )
    }

    /** Point an existing card at a TCGplayer printing (keeps quantity, condition, paid price). */
    fun link(card: Card, p: Printing, usdRate: Double?): Card {
        val firstEdition = card.rarity.startsWith("1st Edition", ignoreCase = true)
        val priced = p.priceUsd != null && usdRate != null
        return card.copy(
            name = p.name,
            setName = p.setName,
            code = p.code,
            rarity = rarityLabel(p.rarity, firstEdition),
            tcgplayerId = p.tcgplayerId,
            imageUrl = if (p.tcgplayerId == null) p.imageUrl else null,
            valuePerUnitCents = if (priced) (p.priceUsd!! * usdRate!! * 100).roundToLong() else card.valuePerUnitCents,
            priceUpdatedAt = if (priced) Dates.now() else card.priceUpdatedAt,
        )
    }

    suspend fun usdRate(currency: String): Double? = runCatching { Exchange.usdTo(currency) }.getOrNull()
}
