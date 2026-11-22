package com.senjumomo.utilities

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStoreTest {
    private fun bill(tokens: String) = Bill(
        "0".toBigDecimal(), "800".toBigDecimal(), "810.5".toBigDecimal(),
        "70".toBigDecimal(), tokens.toBigDecimal(), "100".toBigDecimal(),
    )

    @Test
    fun savesReplacesAndDeletes() {
        val prefs = FakePrefs()
        val store = HistoryStore(prefs)
        assertTrue(store.all().isEmpty())

        store.save(HistoryEntry("2026-10", bill("8"), 1))
        store.save(HistoryEntry("2026-11", bill("10"), 2))
        store.save(HistoryEntry("2026-11", bill("12"), 3)) // replaces November

        val all = HistoryStore(prefs).all() // fresh instance reads from storage
        assertEquals(listOf("2026-11", "2026-10"), all.map { it.month })
        assertEquals(bill("12"), all[0].bill)
        assertEquals("810.5".toBigDecimal(), all[0].bill.waterCurr)

        store.delete("2026-11")
        assertEquals(listOf("2026-10"), store.all().map { it.month })
    }

    @Test
    fun pricesRoundTrip() {
        val prices = PriceStore(FakePrefs())
        assertEquals(null, prices.waterPrice)
        prices.waterPrice = "72.5".toBigDecimal()
        assertEquals("72.5".toBigDecimal(), prices.waterPrice)
        prices.waterPrice = null
        assertEquals(null, prices.waterPrice)
    }
}

/** Minimal in-memory SharedPreferences (strings only) for JVM tests. */
class FakePrefs : SharedPreferences {
    private val map = mutableMapOf<String, String?>()
    override fun getString(key: String, defValue: String?) = map[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { map[key] = value }
        override fun apply() {}
        override fun commit() = true
        override fun remove(key: String) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }
    override fun getAll(): MutableMap<String, *> = map
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
}
