package com.senjumomo.utilities

import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.math.BigDecimal

/**
 * Binds the Calculate page (view_calculate.xml) and recalculates live as the user types.
 * Prices come from Settings via [setPrices]. Kept separate from the Activity so it can be
 * rendered in screenshot tests.
 */
class BillScreen(root: View, private val prefs: SharedPreferences?) {

    private val debt: TextInputEditText = root.findViewById(R.id.debt)
    private val waterPrev: TextInputEditText = root.findViewById(R.id.waterPrev)
    private val waterCurr: TextInputEditText = root.findViewById(R.id.waterCurr)
    private val waterCurrLayout: TextInputLayout = root.findViewById(R.id.waterCurrLayout)
    private val tokens: TextInputEditText = root.findViewById(R.id.tokens)
    private val waterRate: TextView = root.findViewById(R.id.waterRate)
    private val tokenRate: TextView = root.findViewById(R.id.tokenRate)
    private val monthView: TextView = root.findViewById(R.id.month)

    private val waterUsed: TextView = root.findViewById(R.id.waterUsed)
    private val waterTotal: TextView = root.findViewById(R.id.waterTotal)
    private val elecUsed: TextView = root.findViewById(R.id.elecUsed)
    private val elecTotal: TextView = root.findViewById(R.id.elecTotal)
    private val total: TextView = root.findViewById(R.id.total)
    private val totalHint: TextView = root.findViewById(R.id.totalHint)
    private val messageCard: View = root.findViewById(R.id.messageCard)
    private val messageView: TextView = root.findViewById(R.id.message)

    private val fields = mapOf(
        "debt" to debt,
        "waterPrev" to waterPrev,
        "waterCurr" to waterCurr,
        "tokens" to tokens,
    )

    private var pricePerKl: BigDecimal? = null
    private var pricePerToken: BigDecimal? = null

    /** The month this calculation is for, as a "yyyy-MM" key. */
    var month: String = prefs?.getString(KEY_MONTH, null)?.takeIf { it.isNotBlank() } ?: Months.current()
        set(value) {
            field = value
            monthView.text = Months.label(value)
            save()
        }

    /** The finished bill, or null while the form is incomplete. */
    var bill: Bill? = null
        private set

    init {
        prefs?.let { p -> fields.forEach { (key, f) -> f.setText(p.getString(key, "")) } }
        fields.values.forEach { it.doAfterTextChanged { recalculate() } }
        monthView.text = Months.label(month)
        recalculate()
    }

    fun setPrices(water: BigDecimal?, token: BigDecimal?) {
        pricePerKl = water
        pricePerToken = token
        recalculate()
    }

    fun fill(values: Map<String, String>) {
        values.forEach { (key, value) -> fields[key]?.setText(value) }
    }

    fun save() {
        prefs?.edit()?.apply {
            fields.forEach { (key, f) -> putString(key, f.text.toString().trim()) }
            putString(KEY_MONTH, month)
        }?.apply()
    }

    /** Moves on a month: current reading becomes last month's; debt and tokens are cleared. */
    fun startNewMonth(): Boolean {
        val current = waterCurr.text?.toString()?.trim().orEmpty()
        if (current.isEmpty()) return false
        waterPrev.setText(current)
        waterCurr.setText("")
        tokens.setText("")
        debt.setText("")
        month = Months.shift(month, 1)
        return true
    }

    private fun recalculate() {
        val debtValue = Bill.parse(debt.text) ?: BigDecimal.ZERO
        val prev = Bill.parse(waterPrev.text)
        val curr = Bill.parse(waterCurr.text)
        val tokenCount = Bill.parse(tokens.text)
        val klPrice = pricePerKl
        val tokPrice = pricePerToken

        waterRate.text = klPrice?.let { "${Bill.money(it)} per kl" } ?: "Not set yet"
        tokenRate.text = tokPrice?.let { "${Bill.money(it)} per token" } ?: "Not set yet"

        // Water subtotal
        val readingsBad = prev != null && curr != null && curr < prev
        waterCurrLayout.error = if (readingsBad) "Lower than last month" else null
        val used = if (prev != null && curr != null && !readingsBad) curr - prev else null
        val waterCost = if (used != null && klPrice != null) used * klPrice else null
        waterUsed.text = used?.let { "${Bill.num(it)} kl used" } ?: "Usage"
        waterTotal.text = waterCost?.let(Bill::money) ?: "—"

        // Electricity subtotal
        val elecCost = if (tokenCount != null && tokPrice != null) tokenCount * tokPrice else null
        elecUsed.text = tokenCount?.let { "${Bill.num(it)} ${Bill.tokenWord(it)}" } ?: "Tokens"
        elecTotal.text = elecCost?.let(Bill::money) ?: "—"

        if (waterCost != null && elecCost != null) {
            val b = Bill(debtValue, prev!!, curr!!, klPrice!!, tokenCount!!, tokPrice!!)
            bill = b
            total.text = Bill.money(b.owing)
            totalHint.text = "Debt + water + electricity"
            messageView.text = b.message()
            messageCard.visibility = View.VISIBLE
        } else {
            bill = null
            total.text = Bill.money(
                debtValue + (waterCost ?: BigDecimal.ZERO) + (elecCost ?: BigDecimal.ZERO)
            )
            totalHint.text = when {
                readingsBad -> "Check this month's water reading"
                klPrice == null || tokPrice == null -> "Set your prices in Settings"
                else -> "Fill in water and electricity to finish"
            }
            messageCard.visibility = View.GONE
        }
    }

    companion object {
        const val KEY_MONTH = "month"
    }
}
