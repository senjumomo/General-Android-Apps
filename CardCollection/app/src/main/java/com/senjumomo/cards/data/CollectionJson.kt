package com.senjumomo.cards.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads and writes the collection-app JSON export: an array of objects whose values are all
 * strings ("quantity": "1", "value_per_unit": "4.90", "doubleholo_id": "null", ...).
 */
object CollectionJson {

    class ImportException(message: String) : Exception(message)

    fun parse(text: String): List<Card> {
        val array = try {
            JSONArray(text.trim())
        } catch (e: Exception) {
            throw ImportException("This file isn't a collection export (expected a JSON list of cards).")
        }
        val cards = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.str("product_name") ?: return@mapNotNull null
            val quantity = o.str("quantity")?.toBigDecimalOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
            Card(
                id = o.str("id") ?: java.util.UUID.randomUUID().toString().replace("-", ""),
                name = name,
                setName = o.str("set_name").orEmpty(),
                code = o.str("discriminator").orEmpty(),
                rarity = o.str("rarity").orEmpty(),
                quantity = quantity,
                valuePerUnitCents = cents(o.str("value_per_unit"))
                    ?: cents(o.str("value_total"))?.let { it / quantity } ?: 0,
                paidPerUnitCents = cents(o.str("paid_per_unit"))
                    ?: cents(o.str("paid_total"))?.let { it / quantity },
                currency = o.str("value_currency") ?: "ZAR",
                gradeType = o.str("grade_type") ?: "Ungraded",
                condition = o.str("grade_subtype") ?: "Near Mint",
                groupName = o.str("group_name") ?: "Primary",
                wishlist = o.str("group_wishlist") == "true",
                tcgplayerId = o.str("tcgplayer_id"),
                pricechartingId = o.str("pricecharting_id"),
                brand = o.str("brand_name") ?: "Yu-Gi-Oh!",
                dateAdded = o.str("date_added") ?: Dates.now(),
                tag = o.str("tag").orEmpty(),
            )
        }
        if (array.length() > 0 && cards.isEmpty()) {
            throw ImportException("No cards found in this file.")
        }
        return cards
    }

    /** Keeps what the app worked out locally (matched image) when re-importing the same cards. */
    fun keepLocal(imported: List<Card>, existing: List<Card>): List<Card> {
        val byId = existing.associateBy { it.id }
        return imported.map { c -> byId[c.id]?.let { c.copy(imageUrl = it.imageUrl ?: c.imageUrl) } ?: c }
    }

    fun write(cards: List<Card>): String {
        val array = JSONArray()
        cards.sortedBy { it.dateAdded }.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.id)
                put("product_name", c.name)
                put("set_name", c.setName)
                put("brand_name", c.brand)
                put("discriminator", c.code)
                put("rarity", c.rarity)
                put("quantity", c.quantity.toString())
                put("value_total", plain(c.valueTotalCents))
                put("value_per_unit", plain(c.valuePerUnitCents))
                put("value_currency", c.currency)
                put("paid_total", c.paidTotalCents?.let(::plain) ?: "null")
                put("paid_per_unit", c.paidPerUnitCents?.let(::plain) ?: "null")
                put("paid_currency", c.currency)
                put("grade_type", c.gradeType)
                put("grade_subtype", c.condition)
                put("group_name", c.groupName)
                put("group_wishlist", c.wishlist.toString())
                put("tcgplayer_id", c.tcgplayerId ?: "null")
                put("pricecharting_id", c.pricechartingId ?: "null")
                put("doubleholo_id", "null")
                put("date_added", c.dateAdded)
                put("tag", c.tag)
            })
        }
        return array.toString(2)
    }

    /** String value, treating missing, blank and "null" as absent. */
    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)?.toString()?.trim() ?: return null
        return v.takeUnless { it.isEmpty() || it == "null" }
    }

    fun cents(text: String?): Long? =
        text?.trim()?.replace(',', '.')?.toBigDecimalOrNull()
            ?.multiply(BigDecimal(100))?.setScale(0, RoundingMode.HALF_UP)?.toLong()

    fun plain(cents: Long): String = BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString()
}

object Dates {
    fun now(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    /** "2026-08-01T23:17:39.479" -> "1 Aug 2026" */
    fun label(iso: String): String = try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso.take(10))!!
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(d)
    } catch (e: Exception) {
        iso
    }
}
