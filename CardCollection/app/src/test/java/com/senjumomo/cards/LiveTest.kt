package com.senjumomo.cards

import com.senjumomo.cards.data.CardLookup
import com.senjumomo.cards.data.CollectionJson
import com.senjumomo.cards.net.Exchange
import com.senjumomo.cards.net.Tcgplayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real services. Run with LIVE=1 (and optionally CARD_EXPORT=/path/to/export.json). */
class LiveTest {
    @Test
    fun pricesAndLookupWork() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val rate = Exchange.usdTo("ZAR")
        println("USD->ZAR $rate")
        assertTrue(rate > 5)

        val scan = CardLookup.byCode("MAMA-EN081")
        println("Scan MAMA-EN081 -> $scan")
        assertTrue(scan.any { it.name == "A Hero Lives" && it.tcgplayerId == "451061" })

        val alt = com.senjumomo.cards.net.Ygoprodeck.setPrinting("MACR-EN036")
        println("MACR-EN036 artwork -> $alt")
        assertTrue(alt?.artworkId == 14558128L) // Ash Blossom alternate artwork

        val found = CardLookup.search("ash blos")
        println("Search 'ash blos' -> ${found.size} printings, first: ${found.firstOrNull()}")
        assertTrue(found.isNotEmpty() && found.first().name.startsWith("Ash Blossom"))

        val sets = com.senjumomo.cards.packs.PackParsing.parseSets(
            com.senjumomo.cards.net.Http.get("https://db.ygoprodeck.com/api/v7/cardsets.php"))
        val rota = sets.first { it.code == "ROTA" }
        val rotaCards = com.senjumomo.cards.packs.PackParsing.parseSetCards(
            com.senjumomo.cards.net.Http.get("https://db.ygoprodeck.com/api/v7/cardinfo.php?cardset=" +
                com.senjumomo.cards.net.Http.enc(rota.name)), rota.name)
        val pack = com.senjumomo.cards.packs.PackGenerator.open(rotaCards)
        println("${sets.size} booster sets. ${rota.name}: ${rotaCards.size} printings. Pack: " +
            pack.joinToString { "${it.name} (${it.rarity})" })
        assertTrue(pack.size == 9)

        val backOk = (java.net.URL(com.senjumomo.cards.packs.CardBack.OFFICIAL_URL).openConnection() as java.net.HttpURLConnection)
            .apply { setRequestProperty("User-Agent", "CardBinder/2.0 (Android)") }.responseCode
        println("Official card back: HTTP $backOk")
        assertTrue(backOk == 200)

        // Editions are priced separately: Archlord Kristya SOVR-EN096 (1st Edition ≈ 3.7× Unlimited).
        val first = Tcgplayer.marketPriceUsd("33733", "1st Edition Secret Rare")
        val unlimited = Tcgplayer.marketPriceUsd("33733", "Secret Rare")
        println("Archlord Kristya SOVR-EN096: 1st Edition $$first, Unlimited $$unlimited")
        assertTrue(first != null && unlimited != null && first > unlimited * 1.5)

        val export = System.getenv("CARD_EXPORT") ?: return@runBlocking
        val cards = CollectionJson.parse(File(export).readText()).take(8)
        cards.forEach { c ->
            val usd = Tcgplayer.marketPriceUsd(c.tcgplayerId!!, c.rarity)
            val zar = usd?.let { it * rate }
            println("%-40s export R%.2f  now R%s".format(c.name.take(40), c.valuePerUnitCents / 100.0, zar?.let { "%.2f".format(it) } ?: "—"))
        }
    }
}
