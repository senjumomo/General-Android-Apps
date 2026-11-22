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

/** Finds YGOPRODeck images for cards that have no TCGplayer id (e.g. added by hand). */
class ImageResolver(private val dao: CardDao) {
    suspend fun resolveMissing(cards: List<Card>) {
        cards.filter { it.tcgplayerId == null && it.imageUrl == null && it.name.isNotBlank() }
            .take(40)
            .forEach { card ->
                val info = runCatching { com.senjumomo.cards.net.Ygoprodeck.byName(card.name) }
                // Network failure: try again next time. Not found: remember that ("").
                if (info.isSuccess) dao.setImageUrl(card.id, info.getOrNull()?.imageSmall ?: "")
            }
    }
}
