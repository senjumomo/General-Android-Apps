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

    @Test
    fun expectationMatchesOdds() {
        // Modern set: 1 foil slot per pack; Ultra 1/6, Secret 1/12, QCSR 1/24 → Ultra-or-better ≈ 0.29.
        val e = com.senjumomo.cards.packs.PackExpectation.of(modern, packs = 4000)
        assertEquals(1.0 / 6 + 1.0 / 12 + 1.0 / 24, e.ultra, 0.03)
        assertEquals(1.0 / 12 + 1.0 / 24, e.secret, 0.02)
        assertEquals(0.9, e.valueUsd, 0.001) // 9 cards × $0.10
    }

    private fun opening(id: Long, set: String, value: Long, ultra: Double, secret: Double, at: Long = id) =
        com.senjumomo.cards.packs.PackOpening(id, "SET", set, at, "ZAR", value, 1000, ultra, secret)

    private fun pull(opening: Long, set: String, code: String, rarity: String, value: Long?) =
        com.senjumomo.cards.packs.Pull(0, opening, set, code, "Card $code", rarity, Tier.of(rarity).ordinal, 1, value, false)

    @Test
    fun pullSummaryAndLuck() {
        val openings = (1L..10L).map { opening(it, if (it <= 6) "Alpha" else "Beta", 1500, ultra = 0.3, secret = 0.12) }
        val pulls = listOf(
            pull(1, "Alpha", "A-001", "Common", 10), pull(1, "Alpha", "A-001", "Common", 10),
            pull(2, "Alpha", "A-002", "Ultra Rare", 5000), pull(3, "Alpha", "A-003", "Secret Rare", 9000),
            pull(4, "Alpha", "A-004", "Ultra Rare", 200), pull(8, "Beta", "B-001", "Ultra Rare", 100),
            pull(9, "Beta", "B-002", "Super Rare", null),
        )
        val s = com.senjumomo.cards.packs.PullSummary.of(openings, pulls)
        assertEquals(10, s.packs)
        assertEquals(15000L, s.valueCents)
        assertEquals(4, s.ultra)                      // 3 Ultra + 1 Secret
        assertEquals(3.0, s.expectedUltra, 1e-9)
        // Expected Secrets (1.2) ≥ 1, so luck uses Secrets: 1 / 1.2.
        val luck = s.luck()!!
        assertEquals(1 / 1.2, luck.first, 1e-9)
        assertTrue(luck.second.startsWith("1 Secret Rare or better from 10 packs"))
        assertEquals("A-003", s.best.first().code)    // most valuable first
        assertEquals(4, s.progress["alpha"])          // A-001 twice counts once
        assertEquals(2, s.progress["beta"])
        assertEquals("Beta", s.recentSets.first().first)
        // Too few packs: no verdict yet.
        assertEquals(null, com.senjumomo.cards.packs.PullSummary.of(openings.take(2), pulls).luck())
    }

    @Test
    fun binderEntries() {
        val set = listOf(
            SetCard("Ten", "S-EN010", "Common", 10, null, "Effect Monster"),
            SetCard("Two", "S-EN002", "Super Rare", 2, null, "Effect Monster"),
            SetCard("Two", "S-EN002", "Quarter Century Secret Rare", 2, null, "Effect Monster"),
            SetCard("One", "S-EN001", "Common", 1, null, "Pendulum Effect Monster"),
        )
        val pulls = listOf(
            pull(1, "S", "S-EN002", "Super Rare", 1), pull(2, "S", "S-EN002", "Quarter Century Secret Rare", 1),
            pull(2, "S", "S-EN099", "Ultra Rare", 1), // no longer in the set list: still shown
        )
        val e = com.senjumomo.cards.packs.BinderEntry.build(set, pulls)
        assertEquals(listOf("S-EN001", "S-EN002", "S-EN010", "S-EN099"), e.map { it.code })
        assertEquals(false, e[0].pulled)
        assertTrue(e[0].pendulum)
        assertEquals(2, e[1].count)
        assertEquals("Quarter Century Secret Rare", e[1].bestRarity)
        assertEquals(true, e[3].pulled)
    }
}

