package com.senjumomo.cards.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardImages
import com.senjumomo.cards.data.Dates

/** Binds the read-only card screen (activity_detail.xml). */
class CardDetailView(root: View) {
    val image: ImageView = root.findViewById(R.id.image)
    val priceAction: MaterialButton = root.findViewById(R.id.priceAction)
    val tcgplayer: MaterialButton = root.findViewById(R.id.tcgplayer)
    val gotIt: MaterialButton = root.findViewById(R.id.gotIt)

    private val name: TextView = root.findViewById(R.id.name)
    private val meta: TextView = root.findViewById(R.id.meta)
    private val rarity: TextView = root.findViewById(R.id.rarity)
    private val condition: TextView = root.findViewById(R.id.condition)
    private val wishlistPill: TextView = root.findViewById(R.id.wishlistPill)
    private val valueTotal: TextView = root.findViewById(R.id.valueTotal)
    private val valueEach: TextView = root.findViewById(R.id.valueEach)
    private val valueSource: TextView = root.findViewById(R.id.valueSource)
    private val paidCard: View = root.findViewById(R.id.paidCard)
    private val paidEach: TextView = root.findViewById(R.id.paidEach)
    private val paidTotal: TextView = root.findViewById(R.id.paidTotal)
    private val gain: TextView = root.findViewById(R.id.gain)
    private val quantity: TextView = root.findViewById(R.id.quantity)
    private val conditionValue: TextView = root.findViewById(R.id.conditionValue)
    private val setValue: TextView = root.findViewById(R.id.setValue)
    private val added: TextView = root.findViewById(R.id.added)

    /** Show [card]; [refreshing] disables the price button while a refresh runs. */
    fun bind(card: Card, refreshing: Boolean = false) {
        val cur = card.currency
        CardImages.load(image, CardImages.large(card))
        name.text = card.name
        meta.text = listOf(card.code, card.setName).filter { it.isNotBlank() }.joinToString(" · ")
        rarity.text = card.rarity.ifBlank { "Unknown rarity" }
        condition.text = card.condition
        wishlistPill.visibility = if (card.wishlist) View.VISIBLE else View.GONE
        wishlistPill.text = "On wishlist"
        gotIt.visibility = if (card.wishlist) View.VISIBLE else View.GONE

        valueTotal.text = Money.format(card.valueTotalCents, cur)
        valueEach.text = if (card.quantity > 1) {
            "${card.quantity} × ${Money.format(card.valuePerUnitCents, cur)} each"
        } else {
            "${Money.format(card.valuePerUnitCents, cur)} each"
        }
        val linked = card.tcgplayerId != null
        valueSource.text = when {
            !linked -> "No price source. Link this card to TCGplayer to get its market price."
            card.priceUpdatedAt != null -> "TCGplayer market price · updated ${Dates.label(card.priceUpdatedAt)}"
            else -> "TCGplayer market price · from your import"
        }
        priceAction.isEnabled = !refreshing
        priceAction.text = when {
            refreshing -> "Refreshing…"
            linked -> "Refresh price"
            else -> "Find on TCGplayer"
        }
        priceAction.setIconResource(if (linked) R.drawable.ic_refresh else R.drawable.ic_search)

        val paid = card.paidPerUnitCents
        paidCard.visibility = if (card.wishlist) View.GONE else View.VISIBLE
        paidEach.text = paid?.let { Money.format(it, cur) } ?: "Not set"
        paidTotal.text = card.paidTotalCents?.let { Money.format(it, cur) } ?: "—"
        gain.text = paid?.let {
            val g = card.valueTotalCents - card.paidTotalCents!!
            (if (g > 0) "▲ " else if (g < 0) "▼ " else "") + Money.signed(g, cur)
        } ?: "—"

        quantity.text = card.quantity.toString()
        conditionValue.text = "${card.condition} · ${card.gradeType}"
        setValue.text = card.setName.ifBlank { "—" }
        added.text = Dates.label(card.dateAdded)
        tcgplayer.visibility = if (linked) View.VISIBLE else View.GONE
    }
}
