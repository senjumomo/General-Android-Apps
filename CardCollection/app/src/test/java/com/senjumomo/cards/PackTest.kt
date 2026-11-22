package com.senjumomo.cards

import com.senjumomo.cards.packs.PackGenerator
import com.senjumomo.cards.packs.PackParsing
import com.senjumomo.cards.packs.SetCard
import com.senjumomo.cards.packs.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PackTest {
    private fun cards(prefix: String, rarity: String, n: Int) =
        (1..n).map { SetCard("$prefix $it", "SET-EN${prefix.hashCode().mod(9)}%03d".format(it + prefix.length * 100), rarity, it.toLong(), 0.1, "Effect Monster") }

    /** Like Rage of the Abyss: no Rares. */
    private val modern = cards("C", "Common", 50) + cards("S", "Super Rare", 26) + cards("U", "Ultra Rare", 14) +
        cards("X", "Secret Rare", 10) + cards("Q", "Quarter Century Secret Rare", 25)

    /** An older set with Rares. */
    private val classic = cards("C", "Common", 60) + cards("R", "Rare", 20) + cards("S", "Super Rare", 10) +
        cards("U", "Ultra Rare", 8) + cards("X", "Secret Rare", 5)

    /** Rarity Collection style: foils only. */
    private val allFoil = cards("S", "Super Rare", 40) + cards("U", "Ultra Rare", 30) + cards("X", "Secret Rare", 20)

    @Test
    fun packsHaveNineUniqueCardsBestLast() {
        listOf(modern, classic, allFoil).forEach { set ->
            repeat(200) { seed ->
                val pack = PackGenerator.open(set, Random(seed))
                assertEquals(9, pack.size)
                assertEquals("no duplicates", 9, pack.map { it.code }.distinct().size)
                assertEquals("sorted by tier", pack.map { it.tier.ordinal }.sorted(), pack.map { it.tier.ordinal })
            }
        }
    }

    @Test
    fun modernPackStructure() {
        val pack = PackGenerator.open(modern, Random(1))
        assertEquals(7, pack.count { it.rarity == "Common" })
        assertTrue("guaranteed Super or better", pack.count { it.tier >= Tier.SUPER } >= 2)
    }

    @Test
    fun classicPackHasARare() {
        val pack = PackGenerator.open(classic, Random(2))
        assertEquals(7, pack.count { it.rarity == "Common" })
        assertEquals(1, pack.count { it.rarity == "Rare" })
    }

    @Test
    fun pullRatesMatchTheOdds() {
        val random = Random(42)
        val n = 20_000
        var ultras = 0; var secrets = 0; var qc = 0
        repeat(n) {
            val foil = PackGenerator.open(modern, random).last()
            when (foil.rarity) {
                "Ultra Rare" -> ultras++
                "Secret Rare" -> secrets++
                "Quarter Century Secret Rare" -> qc++
            }
        }
        fun near(actual: Int, expected: Double) =
            assertTrue("$actual vs ${expected * n}", kotlin.math.abs(actual - expected * n) < expected * n * 0.12)
        near(ultras, 1.0 / 6)
        near(secrets, 1.0 / 12)
        near(qc, 1.0 / 24)
    }

    @Test
    fun parsesSetsAndCards() {
        val sets = PackParsing.parseSets(
            """[{"set_name":"Rage of the Abyss","set_code":"ROTA","num_of_cards":101,"tcg_date":"2024-10-10","set_image":"https://x/ROTA.jpg"},
               {"set_name":"Structure Deck: Blue-Eyes","set_code":"SDBE","num_of_cards":40,"tcg_date":"2015-01-01"},
               {"set_name":"Tiny Promo","set_code":"TP","num_of_cards":5,"tcg_date":"2020-01-01"},
               {"set_name":"Legend of Blue Eyes White Dragon","set_code":"LOB","num_of_cards":126,"tcg_date":"2002-03-08"}]"""
        )
        assertEquals(listOf("ROTA", "LOB"), sets.map { it.code }) // decks/promos dropped, newest first
        assertEquals(null, sets[1].imageUrl)

        val cards = PackParsing.parseSetCards(
            """{"data":[{"id":1,"name":"Card A","type":"Effect Monster",
               "card_images":[{"id":11}],"card_prices":[{"tcgplayer_price":"2.50"}],
               "card_sets":[{"set_name":"Rage of the Abyss","set_code":"ROTA-EN001","set_rarity":"Super Rare","set_price":"0"},
                            {"set_name":"Rage of the Abyss","set_code":"ROTA-EN001","set_rarity":"Quarter Century Secret Rare","set_price":"40.00"},
                            {"set_name":"Other","set_code":"OTH-EN001","set_rarity":"Common","set_price":"0"}]}]}""",
            "Rage of the Abyss",
        )
        assertEquals(2, cards.size)
        assertEquals(2.5, cards[0].priceUsd!!, 1e-9) // falls back to the card price
        assertEquals(40.0, cards[1].priceUsd!!, 1e-9)
        assertEquals(11L, cards[0].artworkId)
        assertEquals(Tier.PREMIUM, cards[1].tier)
    }
}
