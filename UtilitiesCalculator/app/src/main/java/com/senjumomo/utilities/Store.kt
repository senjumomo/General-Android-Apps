package com.senjumomo.utilities

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Months are stored as "yyyy-MM" keys so they sort correctly as text. */
object Months {
    private val keyFormat get() = SimpleDateFormat("yyyy-MM", Locale.US)

    fun current(): String = keyFormat.format(Calendar.getInstance().time)

    /** [key] moved by [months] (negative = earlier). */
    fun shift(key: String, months: Int): String = try {
        val cal = Calendar.getInstance().apply { time = keyFormat.parse(key)!! }
        cal.add(Calendar.MONTH, months)
        keyFormat.format(cal.time)
    } catch (e: Exception) {
        current()
    }

    /** Choices for the month picker: next month, this month and 22 months back, newest first. */
    fun choices(): List<String> = List(24) { shift(current(), 1 - it) }

    /** "2026-11" -> "Nov" */
    fun short(key: String): String = format(key, "MMM")

    /** "2026-11" -> "Nov 2026" */
    fun medium(key: String): String = format(key, "MMM yyyy")

    private fun format(key: String, pattern: String): String = try {
        SimpleDateFormat(pattern, Locale.getDefault()).format(keyFormat.parse(key)!!)
    } catch (e: Exception) {
        key
    }

    /** "2026-11" -> "November 2026" */
    fun label(key: String): String = try {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(keyFormat.parse(key)!!)
    } catch (e: Exception) {
        key
    }
}

/** Fixed prices configured in Settings. */
class PriceStore(private val prefs: SharedPreferences) {
    var waterPrice: BigDecimal?
        get() = Bill.parse(prefs.getString(WATER, null))
        set(value) = prefs.edit().putString(WATER, value?.toPlainString() ?: "").apply()

    var tokenPrice: BigDecimal?
        get() = Bill.parse(prefs.getString(TOKEN, null))
        set(value) = prefs.edit().putString(TOKEN, value?.toPlainString() ?: "").apply()

    companion object {
        // Same keys the earlier version used for the price fields, so saved prices carry over.
        const val WATER = "waterPrice"
        const val TOKEN = "tokenPrice"
    }
}

/** One saved month. */
data class HistoryEntry(val month: String, val bill: Bill, val savedAt: Long)

/** Saved months, newest first. One entry per month; saving a month again replaces it. */
class HistoryStore(private val prefs: SharedPreferences) {

    fun all(): List<HistoryEntry> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { fromJson(array.getJSONObject(it)) }
                .sortedByDescending { it.month }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun has(month: String) = all().any { it.month == month }

    fun save(entry: HistoryEntry) = write(all().filter { it.month != entry.month } + entry)

    fun delete(month: String) = write(all().filter { it.month != month })

    private fun write(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.sortedByDescending { it.month }.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(e: HistoryEntry) = JSONObject().apply {
        put("month", e.month)
        put("savedAt", e.savedAt)
        put("debt", e.bill.debt.toPlainString())
        put("waterPrev", e.bill.waterPrev.toPlainString())
        put("waterCurr", e.bill.waterCurr.toPlainString())
        put("pricePerKl", e.bill.pricePerKl.toPlainString())
        put("tokens", e.bill.tokens.toPlainString())
        put("pricePerToken", e.bill.pricePerToken.toPlainString())
    }

    private fun fromJson(o: JSONObject) = HistoryEntry(
        month = o.getString("month"),
        savedAt = o.optLong("savedAt"),
        bill = Bill(
            debt = BigDecimal(o.getString("debt")),
            waterPrev = BigDecimal(o.getString("waterPrev")),
            waterCurr = BigDecimal(o.getString("waterCurr")),
            pricePerKl = BigDecimal(o.getString("pricePerKl")),
            tokens = BigDecimal(o.getString("tokens")),
            pricePerToken = BigDecimal(o.getString("pricePerToken")),
        ),
    )

    companion object {
        const val KEY = "history"
    }
}
