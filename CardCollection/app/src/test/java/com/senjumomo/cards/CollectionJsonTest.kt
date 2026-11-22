package com.senjumomo.cards

import com.senjumomo.cards.data.CollectionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CollectionJsonTest {
    private val sample = """
        [
          {
            "id": "4d14a52d60b7484eb78354495c580d58",
            "product_name": "A Hero Lives",
            "set_name": "Magnificent Mavens",
            "brand_name": "Yu-Gi-Oh!",
            "discriminator": "MAMA-EN081",
            "rarity": "1st Edition Ultra Rare",
            "quantity": "2",
            "value_total": "9.80",
            "value_per_unit": "4.90",
            "value_currency": "ZAR",
            "paid_total": "null",
            "paid_per_unit": "null",
            "paid_currency": "ZAR",
            "grade_type": "Ungraded",
            "grade_subtype": "Near Mint",
            "group_name": "Primary",
            "group_wishlist": "false",
            "tcgplayer_id": "451061",
            "pricecharting_id": "4727175",
            "doubleholo_id": "null",
            "date_added": "2026-08-01T23:17:39.479",
            "tag": ""
          }
        ]
    """.trimIndent()

    @Test
    fun parsesExportFormat() {
        val c = CollectionJson.parse(sample).single()
        assertEquals("A Hero Lives", c.name)
        assertEquals("MAMA-EN081", c.code)
        assertEquals(2, c.quantity)
        assertEquals(490L, c.valuePerUnitCents)
        assertEquals(980L, c.valueTotalCents)
        assertNull(c.paidPerUnitCents) // "null" string means no price paid
        assertEquals("451061", c.tcgplayerId)
        assertEquals("Near Mint", c.condition)
    }

    @Test
    fun roundTrips() {
        val cards = CollectionJson.parse(sample)
        assertEquals(cards, CollectionJson.parse(CollectionJson.write(cards)))
        assertEquals(Samples.cards.toSet(), CollectionJson.parse(CollectionJson.write(Samples.cards)).toSet())
    }

    @Test(expected = CollectionJson.ImportException::class)
    fun rejectsNonExport() {
        CollectionJson.parse("""{"not": "a list"}""")
    }

    /** Optional check against a real export: CARD_EXPORT=/path/to/export.json ./gradlew test */
    @Test
    fun realExportIfProvided() {
        val path = System.getenv("CARD_EXPORT")
        assumeTrue(path != null)
        val text = File(path!!).readText()
        val cards = CollectionJson.parse(text)
        val raw = org.json.JSONArray(text)
        assertEquals(raw.length(), cards.size)
        val expectedValue = (0 until raw.length()).sumOf {
            CollectionJson.cents(raw.getJSONObject(it).getString("value_total"))!!
        }
        assertEquals(expectedValue, cards.sumOf { it.valueTotalCents })
        println("Parsed ${cards.size} cards, ${cards.sumOf { it.quantity }} copies, value ${expectedValue / 100.0}")
        assertEquals(cards.toSet(), CollectionJson.parse(CollectionJson.write(cards)).toSet())
    }
}
