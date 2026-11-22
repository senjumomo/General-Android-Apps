package com.senjumomo.cards.net

import org.json.JSONObject

/** YGOPRODeck card database (free, no key). Used for scanning and for fallback images. */
object Ygoprodeck {
    private const val API = "https://db.ygoprodeck.com/api/v7"

    data class Printing(val setName: String, val setCode: String, val rarity: String, val priceUsd: Double?)

    data class CardInfo(
        val id: Long,
        val name: String,
        val type: String,
        val imageSmall: String,
        val printings: List<Printing>,
    )

    fun smallImage(id: Long) = "https://images.ygoprodeck.com/images/cards_small/$id.jpg"
    fun largeImage(small: String) = small.replace("/cards_small/", "/cards/")

    /** Card printed with this set code (e.g. "MAMA-EN081"), or null if unknown. */
    suspend fun bySetCode(code: String): CardInfo? {
        val set = try {
            JSONObject(Http.get("$API/cardsetsinfo.php?setcode=${Http.enc(code)}"))
        } catch (e: Http.HttpException) {
            if (e.code == 400 || e.code == 404) return null else throw e
        }
        if (set.has("error")) return null
        return byName(set.getString("name"))
    }

    /** Exact-name lookup, or null if not found. */
    suspend fun byName(name: String): CardInfo? = try {
        parseCardInfo(Http.get("$API/cardinfo.php?name=${Http.enc(name)}"))
    } catch (e: Http.HttpException) {
        if (e.code == 400 || e.code == 404) null else throw e
    }

    fun parseCardInfo(json: String): CardInfo? {
        val data = JSONObject(json).optJSONArray("data") ?: return null
        val c = data.optJSONObject(0) ?: return null
        val sets = c.optJSONArray("card_sets")
        val printings = (0 until (sets?.length() ?: 0)).map { i ->
            val s = sets!!.getJSONObject(i)
            Printing(
                setName = s.optString("set_name"),
                setCode = s.optString("set_code"),
                rarity = s.optString("set_rarity"),
                priceUsd = s.optString("set_price").toDoubleOrNull()?.takeIf { it > 0 },
            )
        }
        val id = c.getLong("id")
        val image = c.optJSONArray("card_images")?.optJSONObject(0)?.optString("image_url_small")
            ?.takeIf { it.isNotBlank() } ?: smallImage(id)
        return CardInfo(id, c.getString("name"), c.optString("type"), image, printings)
    }
}
