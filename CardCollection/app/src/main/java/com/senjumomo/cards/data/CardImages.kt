package com.senjumomo.cards.data

import android.widget.ImageView
import coil.load
import com.senjumomo.cards.R
import com.senjumomo.cards.net.Tcgplayer
import com.senjumomo.cards.net.Ygoprodeck

/**
 * Picks the best image for a card: YGOPRODeck's high-res scan of that printing's artwork (matched by
 * set code), falling back to TCGplayer's photo until it's been matched or if it can't be.
 */
object CardImages {
    fun small(card: Card): String? =
        card.imageUrl?.takeIf { it.isNotBlank() } ?: card.tcgplayerId?.let(Tcgplayer::smallImage)

    fun large(card: Card): String? =
        card.imageUrl?.takeIf { it.isNotBlank() }?.let(Ygoprodeck::largeImage)
            ?: card.tcgplayerId?.let(Tcgplayer::largeImage)

    fun load(view: ImageView, url: String?) {
        // Show the placeholder straight away (recycled rows would otherwise flash the old image).
        view.setImageResource(R.drawable.card_placeholder)
        if (url == null) return
        view.load(url) {
            crossfade(true)
            placeholder(R.drawable.card_placeholder)
            error(R.drawable.card_placeholder)
        }
    }
}
