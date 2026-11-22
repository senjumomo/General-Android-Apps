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
    fun toCard(p: Printing, firstEdition: Boolean, wishlist: Boolean, existing: List<Card>, usdRate: Double?, currency: String): Card {
        val rarity = rarityLabel(p.rarity, firstEdition)
        findOwned(existing, p.code, rarity, wishlist)?.let { return it.copy(quantity = it.quantity + 1) }
        return Card(
            id = java.util.UUID.randomUUID().toString().replace("-", ""),
            name = p.name,
            setName = p.setName,
            code = p.code,
            rarity = rarity,
            quantity = 1,
            valuePerUnitCents = if (p.priceUsd != null && usdRate != null) (p.priceUsd * usdRate * 100).roundToLong() else 0,
            paidPerUnitCents = null,
            currency = currency,
            wishlist = wishlist,
            tcgplayerId = p.tcgplayerId,
            imageUrl = if (p.tcgplayerId == null) p.imageUrl else null,
            dateAdded = Dates.now(),
            priceUpdatedAt = if (p.priceUsd != null && usdRate != null) Dates.now() else null,
        )
    }

    suspend fun usdRate(currency: String): Double? = runCatching { Exchange.usdTo(currency) }.getOrNull()
}
