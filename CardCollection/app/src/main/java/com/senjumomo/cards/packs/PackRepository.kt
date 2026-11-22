package com.senjumomo.cards.packs

import android.content.Context
import com.senjumomo.cards.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Loads sets and their card lists from YGOPRODeck, cached on the phone so it's quick and light. */
class PackRepository(context: Context) {
    private val dir = File(context.cacheDir, "packs").apply { mkdirs() }

    suspend fun sets(): List<CardSet> =
        PackParsing.parseSets(cached("sets.json", maxAgeDays = 3) { Http.get("$API/cardsets.php") })

    suspend fun cards(set: CardSet): List<SetCard> {
        val file = "set_" + set.code.replace(Regex("[^A-Za-z0-9]"), "_") + ".json"
        return PackParsing.parseSetCards(
            cached(file, maxAgeDays = 7) { Http.get("$API/cardinfo.php?cardset=${Http.enc(set.name)}") },
            set.name,
        )
    }

    /** Fresh-enough cached copy, else download (falling back to a stale copy when offline). */
    private suspend fun cached(name: String, maxAgeDays: Int, download: suspend () -> String): String {
        val file = File(dir, name)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeDays * 86_400_000L
        if (fresh) return withContext(Dispatchers.IO) { file.readText() }
        return try {
            download().also { text -> withContext(Dispatchers.IO) { file.writeText(text) } }
        } catch (e: Exception) {
            if (file.exists()) withContext(Dispatchers.IO) { file.readText() } else throw e
        }
    }

    private companion object {
        const val API = "https://db.ygoprodeck.com/api/v7"
    }
}
