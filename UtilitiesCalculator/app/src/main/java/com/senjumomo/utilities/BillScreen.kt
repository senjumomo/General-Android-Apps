package com.senjumomo.utilities

import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.math.BigDecimal

/**
 * Binds the form in activity_main.xml and recalculates live as the user types.
 * Kept separate from the Activity so it can be rendered in screenshot tests.
 */
class BillScreen(root: View, private val prefs: SharedPreferences?) {

    private val debt: TextInputEditText = root.findViewById(R.id.debt)
    private val waterPrev: TextInputEditText = root.findViewById(R.id.waterPrev)
    private val waterCurr: TextInputEditText = root.findViewById(R.id.waterCurr)
    private val waterCurrLayout: TextInputLayout = root.findViewById(R.id.waterCurrLayout)
    private val waterPrice: TextInputEditText = root.findViewById(R.id.waterPrice)
    private val tokens: TextInputEditText = root.findViewById(R.id.tokens)
    private val tokenPrice: TextInputEditText = root.findViewById(R.id.tokenPrice)

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
        "waterPrice" to waterPrice,
        "tokens" to tokens,
        "tokenPrice" to tokenPrice,
    )

    /** The finished message, or null while the form is incomplete. */
    var message: String? = null
        private set

    init {
        prefs?.let { p -> fields.forEach { (key, f) -> f.setText(p.getString(key, "")) } }
        fields.values.forEach { it.doAfterTextChanged { recalculate() } }
        recalculate()
    }

    fun fill(values: Map<String, String>) {
        values.forEach { (key, value) -> fields[key]?.setText(value) }
    }

    fun save() {
        prefs?.edit()?.apply {
            fields.forEach { (key, f) -> putString(key, f.text.toString().trim()) }
        }?.apply()
    }

    /** Current reading becomes last month's; monthly amounts are cleared, prices kept. */
    fun startNewMonth(): Boolean {
        val current = waterCurr.text?.toString()?.trim().orEmpty()
        if (current.isEmpty()) return false
        waterPrev.setText(current)
        waterCurr.setText("")
        tokens.setText("")
        debt.setText("")
        save()
        waterCurr.requestFocus()
        return true
    }

    private fun recalculate() {
        val debtValue = Bill.parse(debt.text) ?: BigDecimal.ZERO
        val prev = Bill.parse(waterPrev.text)
        val curr = Bill.parse(waterCurr.text)
        val pricePerKl = Bill.parse(waterPrice.text)
        val tokenCount = Bill.parse(tokens.text)
        val pricePerToken = Bill.parse(tokenPrice.text)

        // Water subtotal
        val readingsOk = prev != null && curr != null && curr >= prev
        waterCurrLayout.error =
            if (prev != null && curr != null && curr < prev) "Lower than last month" else null
        val used = if (readingsOk) curr!! - prev!! else null
        val waterCost = if (used != null && pricePerKl != null) used * pricePerKl else null
        waterUsed.text = used?.let { "${Bill.num(it)} kl used" } ?: "Usage"
        waterTotal.text = waterCost?.let(Bill::money) ?: "—"

        // Electricity subtotal
        val elecCost =
            if (tokenCount != null && pricePerToken != null) tokenCount * pricePerToken else null
        elecUsed.text = tokenCount?.let { "${Bill.num(it)} ${Bill.tokenWord(it)}" } ?: "Tokens"
        elecTotal.text = elecCost?.let(Bill::money) ?: "—"

        if (waterCost != null && elecCost != null) {
            val bill = Bill(debtValue, prev!!, curr!!, pricePerKl!!, tokenCount!!, pricePerToken!!)
            total.text = Bill.money(bill.owing)
            totalHint.text = "Debt + water + electricity"
            message = bill.message()
            messageView.text = message
            messageCard.visibility = View.VISIBLE
        } else {
            total.text = Bill.money(debtValue + (waterCost ?: BigDecimal.ZERO) + (elecCost ?: BigDecimal.ZERO))
            totalHint.text = if (waterCurrLayout.error != null) "Check this month's water reading"
            else "Fill in water and electricity to finish"
            message = null
            messageCard.visibility = View.GONE
        }
    }
}
