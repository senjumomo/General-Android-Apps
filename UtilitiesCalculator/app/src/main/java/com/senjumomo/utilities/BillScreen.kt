package com.senjumomo.utilities

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import java.math.BigDecimal

/**
 * Binds the Calculate page and its header (the big total, the split bar) and recalculates live
 * as the user types. [root] is the whole screen, because the header lives in the app bar.
 * Prices come from Settings via [setPrices]. Kept separate from the Activity so it can be
 * rendered in screenshot tests ([animate] = false there).
 */
class BillScreen(
    private val root: View,
    private val prefs: SharedPreferences?,
    private val animate: Boolean = true,
) {

    private val debt: EditText = root.findViewById(R.id.debt)
    private val waterPrev: EditText = root.findViewById(R.id.waterPrev)
    private val waterCurr: EditText = root.findViewById(R.id.waterCurr)
    private val tokens: EditText = root.findViewById(R.id.tokens)
    private val waterCurrTile: View = root.findViewById(R.id.waterCurrTile)
    private val waterError: TextView = root.findViewById(R.id.waterError)
    private val waterRate: TextView = root.findViewById(R.id.waterRate)
    private val tokenRate: TextView = root.findViewById(R.id.tokenRate)
    private val monthView: TextView = root.findViewById(R.id.month)
    private val tokensMinus: ImageButton = root.findViewById(R.id.tokensMinus)
    private val tokensPlus: ImageButton = root.findViewById(R.id.tokensPlus)

    private val waterUsed: TextView = root.findViewById(R.id.waterUsed)
    private val elecUsed: TextView = root.findViewById(R.id.elecUsed)
    private val totalView: TextView = root.findViewById(R.id.total)
    private val totalHint: TextView = root.findViewById(R.id.totalHint)
    private val compactTotal: TextView = root.findViewById(R.id.compactTotal)
    private val splitBar: SplitBarView = root.findViewById(R.id.splitBar)
    private val compactSplit: SplitBarView = root.findViewById(R.id.compactSplit)
    private val messageReady: View = root.findViewById(R.id.messageReady)
    private val messageCard: View = root.findViewById(R.id.messageCard)
    private val messageView: TextView = root.findViewById(R.id.message)
    // The include in activity_main gives the page its id, replacing the scroll view's own.
    private val scroll: NestedScrollView = root.findViewById(R.id.calculatePage)

    private val total = AmountText(totalView)
    private val waterTotal = AmountText(root.findViewById(R.id.waterTotal))
    private val elecTotal = AmountText(root.findViewById(R.id.elecTotal))
    private val legendDebt = AmountText(root.findViewById(R.id.legendDebt))
    private val legendWater = AmountText(root.findViewById(R.id.legendWater))
    private val legendElec = AmountText(root.findViewById(R.id.legendElec))

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

        // Tapping anywhere on a number box (not just on the digits) starts typing in it.
        mapOf(
            R.id.debtTile to debt, R.id.waterPrevTile to waterPrev,
            R.id.waterCurrTile to waterCurr, R.id.tokensTile to tokens,
        ).forEach { (tileId, field) ->
            root.findViewById<View>(tileId).setOnClickListener {
                field.requestFocus()
                field.setSelection(field.text.length)
                (it.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        tokensMinus.setOnClickListener { stepTokens(-1, it) }
        tokensPlus.setOnClickListener { stepTokens(+1, it) }
        messageReady.setOnClickListener { scroll.smoothScrollTo(0, messageCard.top - dp(12)) }

        recalculate(firstRun = true)
    }

    private fun dp(v: Int) = (v * root.resources.displayMetrics.density).toInt()

    private fun stepTokens(delta: Int, button: View) {
        tokens.setText(Stepper.step(tokens.text, delta))
        tokens.setSelection(tokens.text.length)
        Motion.tick(button)
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

    /** The total gives a little pop; used when the month has been saved. */
    fun celebrate() {
        if (animate) Motion.pulse(totalView)
    }

    private fun recalculate(firstRun: Boolean = false) {
        val anim = animate && !firstRun
        val debtValue = Bill.parse(debt.text) ?: BigDecimal.ZERO
        val prev = Bill.parse(waterPrev.text)
        val curr = Bill.parse(waterCurr.text)
        val tokenCount = Bill.parse(tokens.text)
        val klPrice = pricePerKl
        val tokPrice = pricePerToken

        waterRate.text = klPrice?.let { "${Bill.money(it)} / kl" } ?: "Set price"
        tokenRate.text = tokPrice?.let { "${Bill.money(it)} / token" } ?: "Set price"
        tokensMinus.alpha = if ((tokenCount ?: BigDecimal.ZERO).signum() > 0) 1f else 0.4f

        // Water subtotal
        val readingsBad = prev != null && curr != null && curr < prev
        waterCurrTile.isActivated = readingsBad
        waterError.visibility = if (readingsBad) View.VISIBLE else View.GONE
        val used = if (prev != null && curr != null && !readingsBad) curr - prev else null
        val waterCost = if (used != null && klPrice != null) used * klPrice else null
        waterUsed.text = used?.let { "${Bill.num(it)} kl used" } ?: "Usage"
        if (waterCost != null) waterTotal.set(waterCost, anim) else waterTotal.clear("—")

        // Electricity subtotal
        val elecCost = if (tokenCount != null && tokPrice != null) tokenCount * tokPrice else null
        elecUsed.text = tokenCount?.let { "${Bill.num(it)} ${Bill.tokenWord(it)}" } ?: "Tokens"
        if (elecCost != null) elecTotal.set(elecCost, anim) else elecTotal.clear("—")

        // Header: total, split and legend
        val waterPart = waterCost ?: BigDecimal.ZERO
        val elecPart = elecCost ?: BigDecimal.ZERO
        val owing = debtValue + waterPart + elecPart
        total.set(owing, anim)
        compactTotal.text = Bill.money(owing)
        legendDebt.set(debtValue, anim)
        legendWater.set(waterPart, anim)
        legendElec.set(elecPart, anim)
        val shares = Shares.of(debtValue, waterPart, elecPart)
        splitBar.setShares(shares, anim)
        compactSplit.setShares(shares, anim)
        splitBar.contentDescription =
            "Debt ${Bill.money(debtValue)}, water ${Bill.money(waterPart)}, electricity ${Bill.money(elecPart)}"
        compactSplit.contentDescription = splitBar.contentDescription

        if (waterCost != null && elecCost != null) {
            val b = Bill(debtValue, prev!!, curr!!, klPrice!!, tokenCount!!, tokPrice!!)
            bill = b
            totalHint.text = "Debt + water + electricity"
            messageView.text = b.message()
            showMessage(true, anim)
        } else {
            bill = null
            totalHint.text = when {
                readingsBad -> "Check this month's water reading"
                klPrice == null || tokPrice == null -> "Set your prices in Settings"
                else -> "Fill in water and electricity to finish"
            }
            showMessage(false, anim)
        }
    }

    private fun showMessage(show: Boolean, animated: Boolean) {
        val wasShown = messageCard.visibility == View.VISIBLE
        messageReady.visibility = if (show) View.VISIBLE else View.GONE
        messageCard.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !wasShown && animated && Motion.enabled(messageCard)) {
            messageCard.alpha = 0f
            messageCard.translationY = Motion.dp(messageCard, 28f)
            messageCard.animate().alpha(1f).translationY(0f).setDuration(420)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
            messageReady.alpha = 0f
            messageReady.animate().alpha(1f).setDuration(300).start()
        } else if (show) {
            messageCard.alpha = 1f
            messageCard.translationY = 0f
            messageReady.alpha = 1f
        }
    }

    companion object {
        const val KEY_MONTH = "month"
    }
}
