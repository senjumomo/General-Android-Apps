package com.senjumomo.cards.data

/** Finds a Yu-Gi-Oh! set code (e.g. "MAMA-EN081", "LDS3-EN112", "SDK-001") in recognised text. */
object SetCode {
    /** Characters OCR mistakes for digits in the number part, and what they really are. */
    private val digitLookalikes = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'Z' to '2', 'S' to '5', 'B' to '8')
    private const val NUM = "[0-9OQDILZSB]{3}"

    // Set prefix (2-5 letters/digits), a dash (OCR turns it into any dash-like mark, with or
    // without spaces), optional 2-letter region, 3-character number.
    private val withDash = Regex("""\b([A-Z0-9]{2,5})\s?[-‐‑‒–—―_~]\s?([A-Z]{2})?($NUM)\b""")

    // Dash lost entirely ("MAMAEN081"): only accepted with a real region code in the middle.
    private val noDash = Regex("""\b([A-Z0-9]{2,5}?)(EN|EU|AE|SP|FR|DE|IT|PT|KR|JP)($NUM)\b""")

    fun find(text: String): String? = findAll(text).firstOrNull()

    fun findAll(text: String): List<String> {
        val upper = text.uppercase()
        return (withDash.findAll(upper) + noDash.findAll(upper)).sortedBy { it.range.first }.mapNotNull { m ->
            val (prefix, region, number) = m.destructured
            if (prefix.none { it.isLetter() }) return@mapNotNull null   // e.g. a date like 2026-001
            if (number.none { it.isDigit() }) return@mapNotNull null    // a word, not a card number
            "$prefix-$region${number.map { digitLookalikes[it] ?: it }.joinToString("")}"
        }.distinct().toList()
    }
}
