package com.senjumomo.cards.data

import com.senjumomo.cards.net.Exchange
import com.senjumomo.cards.net.Tcgplayer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToLong

/** Refreshes card values from TCGplayer market prices converted to each card's currency. */
class PriceRefresher(private val dao: CardDao) {

    data class Result(
        val updated: Int,
        val unchanged: Int,
        val failed: Int,
        /** Cards without a TCGplayer id, which can't be priced. */
        val skipped: Int,
        val beforeCents: Long,
        val afterCents: Long,
    )

    /** Refresh one card. Returns the updated card, or null if TCGplayer has no price for it. */
    suspend fun refreshOne(card: Card): Card? {
        val id = card.tcgplayerId ?: return null
        val usd = Tcgplayer.marketPriceUsd(id) ?: return null
        val updated = card.copy(
            valuePerUnitCents = (usd * Exchange.usdTo(card.currency) * 100).roundToLong(),
            priceUpdatedAt = Dates.now(),
        )
        dao.upsert(updated)
        return updated
    }

    suspend fun refresh(cards: List<Card>, onProgress: (done: Int, total: Int) -> Unit): Result = coroutineScope {
        val priceable = cards.filter { it.tcgplayerId != null }
        val rates = cards.map { it.currency.uppercase() }.distinct().associateWith { Exchange.usdTo(it) }
        val gate = Semaphore(4) // be gentle with TCGplayer
        var done = 0
        val now = Dates.now()
        val results = priceable.map { card ->
            async {
                gate.withPermit {
                    val usd = runCatching { Tcgplayer.marketPriceUsd(card.tcgplayerId!!) }
                    synchronized(this@PriceRefresher) { onProgress(++done, priceable.size) }
                    card to usd
                }
            }
        }.map { it.await() }

        val changed = mutableListOf<Card>()
        var unchanged = 0
        var failed = 0
        results.forEach { (card, usd) ->
            val price = usd.getOrNull()
            if (price == null) { failed++; return@forEach }
            val cents = (price * rates.getValue(card.currency.uppercase()) * 100).roundToLong()
            if (cents == card.valuePerUnitCents) unchanged++
            changed += card.copy(valuePerUnitCents = cents, priceUpdatedAt = now)
        }
        dao.upsertAll(changed)

        val byId = changed.associateBy { it.id }
        Result(
            updated = changed.size - unchanged,
            unchanged = unchanged,
            failed = failed,
            skipped = cards.size - priceable.size,
            beforeCents = cards.sumOf { it.valueTotalCents },
            afterCents = cards.sumOf { (byId[it.id] ?: it).valueTotalCents },
        )
    }
}

/**
 * Matches cards to YGOPRODeck's high-res images: by set code (gives the exact artwork of that
 * printing), or by name when there's no code. Each card is looked up once and the result saved;
 * "" means "looked, nothing found". Paced well under YGOPRODeck's 20 requests/second limit.
 */
class ImageResolver(private val dao: CardDao) {
    private val tried = mutableSetOf<String>()
    private val byCode = mutableMapOf<String, String>()

    /** Cards this resolver would still look up. */
    fun pending(cards: List<Card>) = cards.filter { it.imageUrl == null && it.id !in tried }

    suspend fun resolveMissing(cards: List<Card>) {
        for (card in pending(cards)) {
            tried += card.id
            val code = card.code.trim().uppercase()
            val url = runCatching {
                when {
                    code.isNotEmpty() -> byCode.getOrPut(code) {
                        com.senjumomo.cards.net.Ygoprodeck.setPrinting(code)
                            ?.let { com.senjumomo.cards.net.Ygoprodeck.smallImage(it.artworkId) }
                            ?: com.senjumomo.cards.net.Ygoprodeck.byName(card.name)?.imageSmall
                            ?: ""
                    }
                    card.name.isNotBlank() -> com.senjumomo.cards.net.Ygoprodeck.byName(card.name)?.imageSmall ?: ""
                    else -> ""
                }
            }
            // On a network error leave it unmatched, so it's tried again next launch.
            url.getOrNull()?.let { dao.setImageUrl(card.id, it) }
            kotlinx.coroutines.delay(150)
        }
    }
}
