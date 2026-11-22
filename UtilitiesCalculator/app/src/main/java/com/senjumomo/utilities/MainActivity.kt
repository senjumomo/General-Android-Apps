package com.senjumomo.utilities

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.RoundingMode

class MainActivity : Activity() {

    private lateinit var debt: EditText
    private lateinit var waterPrev: EditText
    private lateinit var waterCurr: EditText
    private lateinit var waterPrice: EditText
    private lateinit var tokens: EditText
    private lateinit var tokenPrice: EditText
    private lateinit var output: TextView
    private lateinit var actions: LinearLayout

    private val prefs by lazy { getSharedPreferences("utilities", Context.MODE_PRIVATE) }

    /** Field -> preference key, so every value is remembered between launches. */
    private val fields by lazy {
        mapOf(
            "debt" to debt,
            "waterPrev" to waterPrev,
            "waterCurr" to waterCurr,
            "waterPrice" to waterPrice,
            "tokens" to tokens,
            "tokenPrice" to tokenPrice,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        debt = findViewById(R.id.debt)
        waterPrev = findViewById(R.id.waterPrev)
        waterCurr = findViewById(R.id.waterCurr)
        waterPrice = findViewById(R.id.waterPrice)
        tokens = findViewById(R.id.tokens)
        tokenPrice = findViewById(R.id.tokenPrice)
        output = findViewById(R.id.output)
        actions = findViewById(R.id.actions)

        fields.forEach { (key, field) -> field.setText(prefs.getString(key, "")) }

        findViewById<Button>(R.id.calculate).setOnClickListener { calculate() }
        findViewById<Button>(R.id.copy).setOnClickListener { copyMessage() }
        findViewById<Button>(R.id.share).setOnClickListener { shareMessage() }
        findViewById<Button>(R.id.newMonth).setOnClickListener { startNewMonth() }
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun saveFields() {
        prefs.edit().apply {
            fields.forEach { (key, field) -> putString(key, field.text.toString().trim()) }
        }.apply()
    }

    private fun calculate() {
        val debtValue = read(debt, required = false) ?: return
        val prev = read(waterPrev) ?: return
        val curr = read(waterCurr) ?: return
        val pricePerKl = read(waterPrice) ?: return
        val tokenCount = read(tokens) ?: return
        val pricePerToken = read(tokenPrice) ?: return

        if (curr < prev) {
            waterCurr.error = "Current reading can't be lower than the previous reading"
            waterCurr.requestFocus()
            return
        }

        val used = curr - prev
        val waterCost = used * pricePerKl
        val electricityCost = tokenCount * pricePerToken
        val owing = debtValue + waterCost + electricityCost

        val tokenWord = if (tokenCount.compareTo(BigDecimal.ONE) == 0) "token" else "tokens"

        val message = buildString {
            appendLine("Previous Month's Debt: ${money(debtValue)}")
            appendLine()
            appendLine("Water: ${num(curr)}kl - ${num(prev)}kl = ${num(used)}kl x ${money(pricePerKl)} = ${money(waterCost)}")
            appendLine()
            appendLine("Electricity: ${num(tokenCount)} $tokenWord x ${money(pricePerToken)} = ${money(electricityCost)}")
            appendLine()
            append("Owing = ${money(owing)}")
        }

        output.text = message
        output.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        saveFields()
    }

    /** Reads a number from a field. Empty optional fields count as 0. Returns null on error. */
    private fun read(field: EditText, required: Boolean = true): BigDecimal? {
        val text = field.text.toString().trim().replace(',', '.')
        if (text.isEmpty()) {
            if (!required) return BigDecimal.ZERO
            field.error = "Required"
            field.requestFocus()
            return null
        }
        return text.toBigDecimalOrNull() ?: run {
            field.error = "Not a valid number"
            field.requestFocus()
            null
        }
    }

    /** 810 -> "810", 10.50 -> "10.5" */
    private fun num(value: BigDecimal): String =
        value.stripTrailingZeros().toPlainString()

    /** Whole rands stay whole (R700); otherwise two decimals (R12.50). */
    private fun money(value: BigDecimal): String {
        val rounded = value.setScale(2, RoundingMode.HALF_UP)
        val text = if (rounded.stripTrailingZeros().scale() <= 0) {
            rounded.setScale(0).toPlainString()
        } else {
            rounded.toPlainString()
        }
        return "R$text"
    }

    private fun copyMessage() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Utilities", output.text))
        Toast.makeText(this, "Copied - paste it into your chat", Toast.LENGTH_SHORT).show()
    }

    private fun shareMessage() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, output.text.toString())
        }
        startActivity(Intent.createChooser(send, "Send to landlord"))
    }

    /** Moves this month's water reading into "previous" and clears the monthly inputs. */
    private fun startNewMonth() {
        val current = waterCurr.text.toString().trim()
        if (current.isEmpty()) {
            Toast.makeText(this, "Enter the current water reading first", Toast.LENGTH_SHORT).show()
            return
        }
        waterPrev.setText(current)
        waterCurr.setText("")
        tokens.setText("")
        debt.setText("")
        output.visibility = View.GONE
        actions.visibility = View.GONE
        saveFields()
        waterCurr.requestFocus()
        Toast.makeText(this, "Ready for next month. Prices are kept.", Toast.LENGTH_SHORT).show()
    }
}
