package com.senjumomo.cards

import com.senjumomo.cards.data.Card

/** Sample cards shaped like the collection export (made up for tests and screenshots). */
object Samples {
    fun card(
        name: String, set: String, code: String, rarity: String,
        qty: Int, value: Long, paid: Long?, added: String, tcg: String? = "1",
    ) = Card(
        id = code + rarity.hashCode(), name = name, setName = set, code = code, rarity = rarity,
        quantity = qty, valuePerUnitCents = value, paidPerUnitCents = paid, dateAdded = added,
        tcgplayerId = tcg,
    )

    val cards = listOf(
        card("A Hero Lives", "Magnificent Mavens", "MAMA-EN081", "1st Edition Ultra Rare", 1, 490, 364, "2026-08-01T23:17:39.479"),
        card("A Hero Lives", "Legendary Duelists Season 3", "LDS3-EN112", "1st Edition Common", 1, 193, 248, "2026-08-02T00:54:01.714"),
        card("Ash Blossom & Joyous Spring", "Maze of Memories", "MAZE-EN058", "1st Edition Secret Rare", 2, 18950, 15000, "2026-08-01T22:30:00.000"),
        card("Dark Magician", "Legendary Duelists Season 3", "LDS3-EN082", "Ultra Rare", 3, 2575, 2100, "2026-08-02T01:10:00.000"),
        card("Called by the Grave", "Magnificent Mavens", "MAMA-EN099", "1st Edition Common", 1, 1260, null, "2026-08-02T01:20:00.000"),
        card("Blue-Eyes White Dragon", "Legendary Duelists Season 3", "LDS3-EN001", "1st Edition Prismatic Secret Rare", 1, 14999, 16500, "2026-08-01T22:15:00.000"),
        card("Infinite Impermanence", "Maze of Memories", "MAZE-EN070", "1st Edition Super Rare", 2, 3480, 3000, "2026-08-02T00:05:00.000"),
    )
}
