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

        val export = System.getenv("CARD_EXPORT") ?: return@runBlocking
        val cards = CollectionJson.parse(File(export).readText()).take(8)
        cards.forEach { c ->
            val usd = Tcgplayer.marketPriceUsd(c.tcgplayerId!!)
            val zar = usd?.let { it * rate }
            println("%-40s export R%.2f  now R%s".format(c.name.take(40), c.valuePerUnitCents / 100.0, zar?.let { "%.2f".format(it) } ?: "—"))
        }
    }
}
