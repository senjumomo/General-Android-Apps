package com.senjumomo.cards.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line of the collection: a specific printing (set code + rarity + condition) and how many
 * copies are owned. Money is stored in cents to avoid rounding errors.
 */
@Entity(tableName = "cards")
data class Card(
    @PrimaryKey val id: String,
    val name: String,
    val setName: String,
    /** Set code, e.g. "MAMA-EN081". */
    val code: String,
    val rarity: String,
    val quantity: Int,
    val valuePerUnitCents: Long,
    val paidPerUnitCents: Long?,
    val currency: String = "ZAR",
    val gradeType: String = "Ungraded",
    /** Condition, e.g. "Near Mint". */
    val condition: String = "Near Mint",
    val groupName: String = "Primary",
    val wishlist: Boolean = false,
    val tcgplayerId: String? = null,
    val pricechartingId: String? = null,
    val brand: String = "Yu-Gi-Oh!",
    /** ISO-8601 local date-time, e.g. "2026-08-01T23:17:39.479". */
    val dateAdded: String,
    val tag: String = "",
) {
    val valueTotalCents: Long get() = valuePerUnitCents * quantity
    val paidTotalCents: Long? get() = paidPerUnitCents?.let { it * quantity }
}
