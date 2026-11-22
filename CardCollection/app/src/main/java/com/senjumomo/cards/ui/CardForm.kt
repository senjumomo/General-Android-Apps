package com.senjumomo.cards.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CollectionJson
import com.senjumomo.cards.data.Dates

/** Binds the add/edit form (activity_edit.xml). Kept out of the Activity for screenshot tests. */
class CardForm(root: View) {

    private val name: TextInputEditText = root.findViewById(R.id.name)
    private val nameLayout: TextInputLayout = root.findViewById(R.id.nameLayout)
    private val setName: MaterialAutoCompleteTextView = root.findViewById(R.id.setName)
    private val code: TextInputEditText = root.findViewById(R.id.code)
    private val rarity: MaterialAutoCompleteTextView = root.findViewById(R.id.rarity)
    private val condition: MaterialAutoCompleteTextView = root.findViewById(R.id.condition)
    private val quantityView: TextView = root.findViewById(R.id.quantity)
    private val minus: MaterialButton = root.findViewById(R.id.minus)
    private val value: TextInputEditText = root.findViewById(R.id.value)
    private val valueLayout: TextInputLayout = root.findViewById(R.id.valueLayout)
    private val paid: TextInputEditText = root.findViewById(R.id.paid)
    private val paidLayout: TextInputLayout = root.findViewById(R.id.paidLayout)
    private val totals: TextView = root.findViewById(R.id.totals)
    private val infoCard: View = root.findViewById(R.id.infoCard)
    private val added: TextView = root.findViewById(R.id.added)
    val tcgplayer: MaterialButton = root.findViewById(R.id.tcgplayer)

    private var original: Card? = null
    private var currency = "ZAR"
    var quantity = 1
        set(v) {
            field = v.coerceIn(1, 999)
            quantityView.text = field.toString()
            minus.isEnabled = field > 1
            updateTotals()
        }

    init {
        minus.setOnClickListener { quantity-- }
        root.findViewById<MaterialButton>(R.id.plus).setOnClickListener { quantity++ }
        condition.setSimpleItems(CONDITIONS.toTypedArray())
        value.doAfterTextChanged { valueLayout.error = null; updateTotals() }
        paid.doAfterTextChanged { paidLayout.error = null; updateTotals() }
        name.doAfterTextChanged { nameLayout.error = null }
        bind(null, emptyList(), emptyList())
    }

    /** Fill the form from [card] (null = new card) and set autocomplete suggestions. */
    fun bind(card: Card?, sets: List<String>, rarities: List<String>) {
        original = card
        currency = card?.currency ?: "ZAR"
        val symbol = Money.symbol(currency).trim()
        valueLayout.prefixText = symbol
        paidLayout.prefixText = symbol
        setName.setAdapter(ArrayAdapter(setName.context, android.R.layout.simple_list_item_1, sets))
        rarity.setAdapter(
            ArrayAdapter(rarity.context, android.R.layout.simple_list_item_1, (rarities + RARITIES).distinct())
        )
        name.setText(card?.name.orEmpty())
        setName.setText(card?.setName.orEmpty(), false)
        code.setText(card?.code.orEmpty())
        rarity.setText(card?.rarity.orEmpty(), false)
        condition.setText(card?.condition ?: "Near Mint", false)
        value.setText(card?.valuePerUnitCents?.let(CollectionJson::plain).orEmpty())
        paid.setText(card?.paidPerUnitCents?.let(CollectionJson::plain).orEmpty())
        quantity = card?.quantity ?: 1

        infoCard.visibility = if (card == null) View.GONE else View.VISIBLE
        added.text = card?.let { "Added ${Dates.label(it.dateAdded)} · ${it.gradeType}" }.orEmpty()
        tcgplayer.visibility = if (card?.tcgplayerId != null) View.VISIBLE else View.GONE
    }

    private fun updateTotals() {
        val v = CollectionJson.cents(value.text?.toString())
        val p = CollectionJson.cents(paid.text?.toString())
        totals.text = buildString {
            append("Total value: ${v?.let { Money.format(it * quantity, currency) } ?: "—"}")
            if (p != null) {
                append("\nTotal paid: ${Money.format(p * quantity, currency)}")
                if (v != null) append("\nGain: ${Money.signed((v - p) * quantity, currency)}")
            }
        }
    }

    /** The edited card, or null (with errors shown) if the form is invalid. */
    fun read(): Card? {
        val n = name.text?.toString()?.trim().orEmpty()
        val valueText = value.text?.toString()?.trim().orEmpty()
        val paidText = paid.text?.toString()?.trim().orEmpty()
        val v = if (valueText.isEmpty()) 0L else CollectionJson.cents(valueText)
        val p = if (paidText.isEmpty()) null else CollectionJson.cents(paidText)
        var ok = true
        if (n.isEmpty()) { nameLayout.error = "Enter the card name"; ok = false }
        if (v == null || v < 0) { valueLayout.error = "Not a valid amount"; ok = false }
        if (paidText.isNotEmpty() && (p == null || p < 0)) { paidLayout.error = "Not a valid amount"; ok = false }
        if (!ok) return null

        val base = original ?: Card(
            id = java.util.UUID.randomUUID().toString().replace("-", ""),
            name = n, setName = "", code = "", rarity = "", quantity = 1,
            valuePerUnitCents = 0, paidPerUnitCents = null, dateAdded = Dates.now(),
        )
        return base.copy(
            name = n,
            setName = setName.text?.toString()?.trim().orEmpty(),
            code = code.text?.toString()?.trim()?.uppercase().orEmpty(),
            rarity = rarity.text?.toString()?.trim().orEmpty(),
            condition = condition.text?.toString()?.trim()?.ifEmpty { null } ?: "Near Mint",
            quantity = quantity,
            valuePerUnitCents = v!!,
            paidPerUnitCents = p,
        )
    }

    companion object {
        val CONDITIONS = listOf(
            "Mint", "Near Mint", "Lightly Played", "Moderately Played", "Heavily Played", "Damaged",
        )
        val RARITIES = listOf(
            "Common", "Rare", "Super Rare", "Ultra Rare", "Secret Rare", "Ultimate Rare",
            "Ghost Rare", "Starlight Rare", "Quarter Century Secret Rare", "Prismatic Secret Rare",
            "Collector's Rare", "Platinum Secret Rare",
            "1st Edition Common", "1st Edition Rare", "1st Edition Super Rare", "1st Edition Ultra Rare",
            "1st Edition Secret Rare",
        )
    }
}
