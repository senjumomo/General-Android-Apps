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

    /** Market price in USD, or null if TCGplayer has none. */
    suspend fun marketPriceUsd(productId: String): Double? =
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
