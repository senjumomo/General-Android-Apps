package com.senjumomo.cards.net

import org.json.JSONArray

/**
 * TCGplayer market prices (USD). TCGplayer's official API is closed to new developers, so this
 * uses the public endpoint their own website calls. It is unofficial and may change.
 */
object Tcgplayer {
    fun smallImage(productId: String) = "https://tcgplayer-cdn.tcgplayer.com/product/${productId}_200w.jpg"
    fun largeImage(productId: String) = "https://tcgplayer-cdn.tcgplayer.com/product/${productId}_in_1000x1000.jpg"
    fun productPage(productId: String) = "https://www.tcgplayer.com/product/$productId"

    /**
     * Market price in USD of a card's own edition (1st Edition / Unlimited / Limited, from its
     * [rarity]) in Near Mint, or null if TCGplayer has none.
     *
     * The product-level price ([pricePoints]) blends editions: for older cards printed in both 1st
     * Edition and Unlimited it's the Unlimited price (Archlord Kristya SOVR-EN096: $99.99 vs $370 for
     * 1st Edition). TCGplayer's product page shows per-edition prices from its SKUs, so use those.
     */
    suspend fun marketPriceUsd(productId: String, rarity: String): Double? {
        val sku = runCatching { pickSku(skus(productId), rarity) }.getOrNull()
        val price = sku?.let { runCatching { skuMarketPrices(listOf(it.id))[it.id] }.getOrNull() }
        return price ?: pricePoints(productId)
    }

    /** The product's blended market price (fallback when there's no SKU price). */
    suspend fun pricePoints(productId: String): Double? =
        parsePricePoints(Http.get("https://mpapi.tcgplayer.com/v2/product/${Http.enc(productId)}/pricepoints"))

    /** Prefers the market price of the normal printing, then foil, then the listed median. */
    fun parsePricePoints(json: String): Double? {
        val points = JSONArray(json)
        val list = (0 until points.length()).map { points.getJSONObject(it) }
        fun price(o: org.json.JSONObject, key: String) =
            if (o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() && it > 0 }
        val ordered = list.sortedBy { if (it.optString("printingType") == "Normal") 0 else 1 }
        return ordered.firstNotNullOfOrNull { price(it, "marketPrice") }
            ?: ordered.firstNotNullOfOrNull { price(it, "listedMedianPrice") }
    }

    /** One sellable version of a product: edition ("variant") + condition + language. */
    data class Sku(val id: Long, val variant: String, val condition: String, val language: String)

    suspend fun skus(productId: String): List<Sku> =
        parseSkus(Http.get("https://mp-search-api.tcgplayer.com/v2/product/${Http.enc(productId)}/details"))

    fun parseSkus(json: String): List<Sku> {
        val arr = org.json.JSONObject(json).optJSONArray("skus") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Sku(it.getDouble("sku").toLong(), it.optString("variant"), it.optString("condition"), it.optString("language"))
        }
    }

    /** Market prices (USD) for many SKUs at once; SKUs without a price are left out. */
    suspend fun skuMarketPrices(ids: List<Long>): Map<Long, Double> {
        val out = mutableMapOf<Long, Double>()
        ids.distinct().chunked(100).forEach { chunk ->
            val body = """{"skuIds":[${chunk.joinToString(",")}]}"""
            out += parseSkuPrices(Http.post("https://mpgateway.tcgplayer.com/v1/pricepoints/marketprice/skus/search", body))
        }
        return out
    }

    fun parseSkuPrices(json: String): Map<Long, Double> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val p = if (o.isNull("marketPrice")) null else o.optDouble("marketPrice").takeIf { !it.isNaN() && it > 0 }
            p?.let { o.getDouble("skuId").toLong() to it }
        }.toMap()
    }

    /** Which edition a rarity label means, in TCGplayer's words. */
    fun edition(rarity: String): String = when {
        rarity.contains("1st Edition", true) -> "1st Edition"
        rarity.contains("Limited", true) -> "Limited"
        else -> "Unlimited"
    }

    /**
     * The Near Mint English SKU of the card's edition. If the product only comes in one edition
     * (most modern cards: 1st Edition only), that one, whatever the label says.
     */
    fun pickSku(skus: List<Sku>, rarity: String): Sku? {
        val nm = skus.filter { it.condition.equals("Near Mint", true) && it.language.equals("English", true) }
        val wanted = edition(rarity)
        return nm.firstOrNull { it.variant.equals(wanted, true) }
            ?: nm.firstOrNull { wanted == "Limited" && it.variant.contains("Limited", true) }
            ?: nm.singleOrNull()
    }
}

/** One TCGplayer product (a specific printing). */
data class TcgProduct(
    val productId: String,
    val name: String,
    val setName: String,
    val number: String,
    val rarity: String,
    val marketPriceUsd: Double?,
)

object TcgSearch {
    private const val URL = "https://mp-search-api.tcgplayer.com/v1/search/request?isList=false&q="

    /** Yu-Gi-Oh! products matching [query] (a set code or a card name). Unofficial endpoint. */
    suspend fun search(query: String, size: Int = 24, fuzzy: Boolean = false): List<TcgProduct> {
        val body = """
            {"algorithm":"sales_synonym_v2","from":0,"size":$size,
             "filters":{"term":{"productLineName":["yugioh"]},"range":{},"match":{}},
             "listingSearch":{"context":{"cart":{}},"filters":{"term":{},"range":{},"exclude":{"channelExclusion":0}}},
             "context":{"cart":{},"shippingCountry":"US","userProfile":{}},
             "settings":{"useFuzzySearch":$fuzzy,"didYouMean":{}},"sort":{}}
        """.trimIndent()
        return parseSearch(Http.post(URL + Http.enc(query), body))
    }

    /** Printings whose card number is exactly [code]. */
    suspend fun byCode(code: String): List<TcgProduct> =
        search(code).filter { it.number.equals(code, ignoreCase = true) }

    fun parseSearch(json: String): List<TcgProduct> {
        val results = org.json.JSONObject(json).getJSONArray("results").getJSONObject(0).getJSONArray("results")
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            TcgProduct(
                productId = r.getDouble("productId").toLong().toString(),
                name = r.optString("productName"),
                setName = r.optString("setName"),
                number = r.optJSONObject("customAttributes")?.optString("number").orEmpty(),
                rarity = r.optString("rarityName"),
                marketPriceUsd = if (r.isNull("marketPrice")) null else r.optDouble("marketPrice").takeIf { it > 0 },
            )
        }
    }
}

/** Exchange rates (open.er-api.com, free, updated daily). */
object Exchange {
    private var rates: org.json.JSONObject? = null
    private var fetchedAt = 0L

    /** How many [currency] one US dollar buys. Cached for an hour. */
    suspend fun usdTo(currency: String): Double {
        if (currency.equals("USD", true)) return 1.0
        val now = System.currentTimeMillis()
        val cached = rates?.takeIf { now - fetchedAt < 60 * 60 * 1000 }
        val r = cached ?: org.json.JSONObject(Http.get("https://open.er-api.com/v6/latest/USD"))
            .getJSONObject("rates").also { rates = it; fetchedAt = now }
        return r.getDouble(currency.uppercase())
    }
}
