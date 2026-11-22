package com.senjumomo.cards

import com.senjumomo.cards.data.CardLookup
import com.senjumomo.cards.data.Printing
import com.senjumomo.cards.data.SetCode
import com.senjumomo.cards.net.TcgSearch
import com.senjumomo.cards.net.Tcgplayer
import com.senjumomo.cards.net.Ygoprodeck
import com.senjumomo.cards.ui.Breakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookupTest {

    @Test
    fun readsSetCodesFromOcrText() {
        assertEquals("MAMA-EN081", SetCode.find("A HERO LIVES\nMAMA-EN081\n1st Edition"))
        assertEquals("LDS3-EN112", SetCode.find("ldS3-en112"))
        assertEquals("SDK-001", SetCode.find("SDK-001 Limited"))
        assertEquals("RA01-EN001", SetCode.find("RA01 - EN0O1")) // spaces and O-for-0
        assertNull(SetCode.find("2026-001 ATK/2500 DEF/2100"))
        assertNull(SetCode.find("no code here"))
    }

    @Test
    fun parsesTcgplayerPrices() {
        assertEquals(0.28, Tcgplayer.parsePricePoints(
            """[{"printingType":"Normal","marketPrice":0.28,"listedMedianPrice":0.33},
               {"printingType":"Foil","marketPrice":null,"listedMedianPrice":null}]"""
        )!!, 1e-9)
        // No market price: fall back to listed median.
        assertEquals(1.5, Tcgplayer.parsePricePoints(
            """[{"printingType":"Normal","marketPrice":null,"listedMedianPrice":1.5}]"""
        )!!, 1e-9)
        assertNull(Tcgplayer.parsePricePoints("""[{"printingType":"Normal","marketPrice":null,"listedMedianPrice":null}]"""))
    }

    @Test
    fun parsesTcgplayerSearch() {
        val p = TcgSearch.parseSearch(
            """{"results":[{"results":[{"productId":451061.0,"productName":"A Hero Lives","setName":"Magnificent Mavens",
               "rarityName":"Ultra Rare","marketPrice":0.28,"customAttributes":{"number":"MAMA-EN081"}}]}]}"""
        ).single()
        assertEquals("451061", p.productId)
        assertEquals("MAMA-EN081", p.number)
        assertEquals(0.28, p.marketPriceUsd!!, 1e-9)
    }

    @Test
    fun parsesYgoprodeck() {
        val info = Ygoprodeck.parseCardInfo(
            """{"data":[{"id":46986414,"name":"Dark Magician","type":"Normal Monster",
               "card_sets":[{"set_name":"Legendary Duelists: Season 3","set_code":"LDS3-EN082","set_rarity":"Ultra Rare","set_price":"1.25"},
                            {"set_name":"X","set_code":"X-EN001","set_rarity":"Common","set_price":"0"}],
               "card_images":[{"image_url_small":"https://images.ygoprodeck.com/images/cards_small/46986414.jpg"}]}]}"""
        )!!
        assertEquals("Dark Magician", info.name)
        assertEquals(2, info.printings.size)
        assertEquals(1.25, info.printings[0].priceUsd!!, 1e-9)
        assertNull(info.printings[1].priceUsd) // "0" means unknown
        assertEquals("https://images.ygoprodeck.com/images/cards/46986414.jpg", Ygoprodeck.largeImage(info.imageSmall))
    }

    @Test
    fun scannedCardsMatchExistingRarities() {
        assertEquals("Common", CardLookup.baseRarity("Common / Short Print"))
        assertEquals("Ultra Rare", CardLookup.baseRarity("1st Edition Ultra Rare"))

        val printing = Printing("A Hero Lives", "Magnificent Mavens", "MAMA-EN081", "Ultra Rare", 0.28, "451061", null)
        // Already own the 1st Edition Ultra Rare -> one more copy of that card.
        val more = CardLookup.toCard(printing, firstEdition = true, wishlist = false, Samples.cards, 17.5, "ZAR")
        assertEquals(Samples.cards[0].id, more.id)
        assertEquals(2, more.quantity)
        // Unlimited edition is a different printing -> new card, priced from TCGplayer.
        val fresh = CardLookup.toCard(printing, firstEdition = false, wishlist = false, Samples.cards, 17.5, "ZAR")
        assertEquals("Ultra Rare", fresh.rarity)
        assertEquals(1, fresh.quantity)
        assertEquals(490L, fresh.valuePerUnitCents)
        assertEquals("451061", fresh.tcgplayerId)
    }

    @Test
    fun breaksDownValueAndFoldsTheRest() {
        val (top, other) = Breakdown.of(Samples.cards, 12) { it.setName }
        assertEquals(3, top.size)
        assertNull(other)
        assertEquals("Maze of Memories", top.first().key) // 2×189.50 + 2×34.80 is the biggest
        val (two, rest) = Breakdown.of(Samples.cards, 2) { it.setName }
        assertEquals(1, two.size)
        assertEquals("Other (2)", rest!!.key)
        assertEquals(Samples.cards.sumOf { it.valueTotalCents }, two.sumOf { it.valueCents } + rest.valueCents)
    }

    @Test
    fun ranksNameMatchesFirst() {
        fun p(name: String) = Printing(name, "S", "X-EN001", "Common", null, null, null)
        val ranked = CardLookup.rank("dark magi", listOf(
            p("L Magi Mergy"), p("Toon Dark Magician"), p("Dark Magician"), p("Dark Magical Curtain"),
        ))
        assertEquals(listOf("Dark Magician", "Dark Magical Curtain", "Toon Dark Magician", "L Magi Mergy"), ranked.map { it.name })
        assertEquals("Ash Blossom & Joyous Spring", CardLookup.cleanName("Ash Blossom & Joyous Spring (LAVD-ENO08)", "LAVD-ENO08"))
    }

    @Test
    fun linkingKeepsYourDetails() {
        val handAdded = Samples.cards[4].copy(tcgplayerId = null, valuePerUnitCents = 0, paidPerUnitCents = 1000, quantity = 2)
        val printing = Printing("Called by the Grave", "Magnificent Mavens", "MAMA-EN099", "Common", 0.5, "999", null)
        val linked = CardLookup.link(handAdded, printing, 18.0)
        assertEquals("999", linked.tcgplayerId)
        assertEquals(900L, linked.valuePerUnitCents)
        assertEquals("1st Edition Common", linked.rarity) // keeps the edition it had
        assertEquals(2, linked.quantity)
        assertEquals(1000L, linked.paidPerUnitCents)
    }

    @Test
    fun prefersHighResMatchedImages() {
        val c = Samples.cards[0].copy(tcgplayerId = "451061")
        assertEquals("https://tcgplayer-cdn.tcgplayer.com/product/451061_200w.jpg", com.senjumomo.cards.data.CardImages.small(c))
        val matched = c.copy(imageUrl = "https://images.ygoprodeck.com/images/cards_small/8949584.jpg")
        assertEquals(matched.imageUrl, com.senjumomo.cards.data.CardImages.small(matched))
        assertEquals("https://images.ygoprodeck.com/images/cards/8949584.jpg", com.senjumomo.cards.data.CardImages.large(matched))
        // "" = looked, nothing found -> TCGplayer
        assertEquals("https://tcgplayer-cdn.tcgplayer.com/product/451061_in_1000x1000.jpg",
            com.senjumomo.cards.data.CardImages.large(c.copy(imageUrl = "")))
    }

    @Test
    fun reimportKeepsMatchedImages() {
        val existing = listOf(Samples.cards[0].copy(imageUrl = "x"))
        val imported = listOf(Samples.cards[0].copy(quantity = 5), Samples.cards[1])
        val merged = com.senjumomo.cards.data.CollectionJson.keepLocal(imported, existing)
        assertEquals("x", merged[0].imageUrl)
        assertEquals(5, merged[0].quantity)
        assertNull(merged[1].imageUrl)
    }

    @Test
    fun editionSkus() {
        // Trimmed from TCGplayer's product details for Archlord Kristya (SOVR-EN096).
        val skus = Tcgplayer.parseSkus("""{"productId":33733,"skus":[
            {"sku":379273,"condition":"Near Mint","variant":"Unlimited","language":"English"},
            {"sku":573519,"condition":"Lightly Played","variant":"Unlimited","language":"English"},
            {"sku":379787,"condition":"Near Mint","variant":"1st Edition","language":"English"},
            {"sku":540663,"condition":"Lightly Played","variant":"1st Edition","language":"English"}]}""")
        assertEquals(4, skus.size)
        assertEquals(379787L, Tcgplayer.pickSku(skus, "1st Edition Secret Rare")!!.id)
        assertEquals(379273L, Tcgplayer.pickSku(skus, "Secret Rare")!!.id)
        // Modern cards come in one edition only: use it even if the label doesn't say "1st Edition".
        val modern = skus.filter { it.variant == "1st Edition" }
        assertEquals(379787L, Tcgplayer.pickSku(modern, "Ultra Rare")!!.id)
        assertNull(Tcgplayer.pickSku(emptyList(), "Ultra Rare"))

        val prices = Tcgplayer.parseSkuPrices("""[
            {"skuId":379273,"marketPrice":99.99,"lowestPrice":99.99},
            {"skuId":379787,"marketPrice":370.00,"lowestPrice":350},
            {"skuId":540663,"marketPrice":null}]""")
        assertEquals(370.0, prices[379787L]!!, 1e-9)
        assertEquals(99.99, prices[379273L]!!, 1e-9)
        assertNull(prices[540663L])
    }
}

