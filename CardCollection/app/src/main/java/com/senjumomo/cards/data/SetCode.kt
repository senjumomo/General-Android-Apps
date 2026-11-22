package com.senjumomo.cards.data

/** Finds a Yu-Gi-Oh! set code (e.g. "MAMA-EN081", "LDS3-EN112", "SDK-001") in recognised text. */
object SetCode {
    // Set prefix (2-5 letters/digits), dash, optional 2-letter region, 3-digit number.
    // OCR often reads 0 as O in the number, so allow O there and fix it up.
    private val pattern = Regex("""\b([A-Z0-9]{2,5})\s?-\s?([A-Z]{2})?([0-9O]{3})\b""")

    fun find(text: String): String? = findAll(text).firstOrNull()

    fun findAll(text: String): List<String> =
        pattern.findAll(text.uppercase()).mapNotNull { m ->
            val (prefix, region, number) = m.destructured
            if (prefix.none { it.isLetter() }) return@mapNotNull null // e.g. a date like 2026-001
            "$prefix-$region${number.replace('O', '0')}"
        }.toList()
}
