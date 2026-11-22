package com.senjumomo.cards.data

import com.senjumomo.cards.net.Exchange
import com.senjumomo.cards.net.Tcgplayer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        val usd = Tcgplayer.marketPriceUsd(id, card.rarity) ?: return null
        val updated = card.copy(
            valuePerUnitCents = (usd * Exchange.usdTo(card.currency) * 100).roundToLong(),
            priceUpdatedAt = Dates.now(),
        )
        dao.upsert(updated)
        return updated
    }

    companion object {
        private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

        /**
         * Re-price a just-saved card with its own edition's price. Search results only carry the
         * blended product price, which is wrong for older cards printed in more than one edition.
         * Keeps running if the screen closes.
         */
        fun repriceSoon(dao: CardDao, card: Card) {
            if (card.tcgplayerId == null) return
            background.launch { runCatching { PriceRefresher(dao).refreshOne(card) } }
        }
    }

    suspend fun refresh(cards: List<Card>, onProgress: (done: Int, total: Int) -> Unit): Result = coroutineScope {
        val priceable = cards.filter { it.tcgplayerId != null }
        val rates = cards.map { it.currency.uppercase() }.distinct().associateWith { Exchange.usdTo(it) }
        val gate = Semaphore(4) // be gentle with TCGplayer
        var done = 0
        val now = Dates.now()
        // 1. Which SKU (edition + Near Mint) each card is, one product lookup per distinct product.
        val products = priceable.map { it.tcgplayerId!! }.distinct()
        val skusByProduct = products.map { id ->
            async {
                gate.withPermit {
                    val skus = runCatching { Tcgplayer.skus(id) }.getOrNull()
                    synchronized(this@PriceRefresher) { onProgress(++done, products.size + 1) }
                    id to skus
                }
            }
        }.map { it.await() }.toMap()
        val skuOf = priceable.associate { it.id to skusByProduct[it.tcgplayerId]?.let { s -> Tcgplayer.pickSku(s, it.rarity) } }
        // 2. All their market prices in a few batched requests.
        val skuPrices = runCatching { Tcgplayer.skuMarketPrices(skuOf.values.mapNotNull { it?.id }) }.getOrDefault(emptyMap())
        onProgress(products.size + 1, products.size + 1)
        // 3. Cards without a SKU price fall back to the product's blended price.
        val results = priceable.map { card ->
            async {
                val sku = skuOf[card.id]?.let { skuPrices[it.id] }
                card to if (sku != null) kotlin.Result.success(sku) else gate.withPermit {
                    runCatching { Tcgplayer.pricePoints(card.tcgplayerId!!) }
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
