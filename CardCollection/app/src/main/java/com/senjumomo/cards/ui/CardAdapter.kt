package com.senjumomo.cards.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card

/** Rows of the main list: a summary header, then one row per card. */
sealed interface Row {
    data class Header(val summary: Summary, val filtered: Boolean) : Row
    data class Item(val card: Card) : Row
}

class CardAdapter(private val onClick: (Card) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    /** Diffs synchronously so updates also render in screenshot tests. */
    fun submit(newRows: List<Row>) {
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]; val b = newRows[n]
                return (a is Row.Header && b is Row.Header) ||
                    (a is Row.Item && b is Row.Item && a.card.id == b.card.id)
            }
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newRows[n]
        })
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> R.layout.item_summary
        is Row.Item -> R.layout.item_card
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return if (viewType == R.layout.item_summary) HeaderHolder(view) else CardHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Item -> (holder as CardHolder).bind(row.card)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val valueLabel: TextView = view.findViewById(R.id.valueLabel)
        private val value: TextView = view.findViewById(R.id.value)
        private val copies: TextView = view.findViewById(R.id.copies)
        private val unique: TextView = view.findViewById(R.id.unique)
        private val gain: TextView = view.findViewById(R.id.gain)

        fun bind(row: Row.Header) {
            val s = row.summary
            valueLabel.text = if (row.filtered) "Value of matching cards" else "Collection value"
            value.text = Money.format(s.valueCents, s.currency)
            copies.text = "%,d".format(s.copies)
            unique.text = "%,d".format(s.unique)
            gain.text = if (s.paidCents == 0L) "—" else {
                val arrow = when {
                    s.gainCents > 0 -> "▲ "
                    s.gainCents < 0 -> "▼ "
                    else -> ""
                }
                arrow + Money.signed(s.gainCents, s.currency)
            }
        }
    }

    private inner class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.name)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val rarity: TextView = view.findViewById(R.id.rarity)
        private val value: TextView = view.findViewById(R.id.value)
        private val quantity: TextView = view.findViewById(R.id.quantity)

        fun bind(card: Card) {
            name.text = card.name
            meta.text = listOf(card.code, card.setName).filter { it.isNotBlank() }.joinToString(" · ")
            rarity.text = card.rarity.ifBlank { "Unknown rarity" }
            value.text = Money.format(card.valueTotalCents, card.currency)
            quantity.text = if (card.quantity > 1) {
                "×${card.quantity} · ${Money.format(card.valuePerUnitCents, card.currency)} each"
            } else {
                "×1"
            }
            itemView.setOnClickListener { onClick(card) }
        }
    }
}
