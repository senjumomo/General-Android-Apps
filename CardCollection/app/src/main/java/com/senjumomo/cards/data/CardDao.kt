package com.senjumomo.cards.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards")
    fun observeAll(): Flow<List<Card>>

    @Query("SELECT * FROM cards")
    suspend fun all(): List<Card>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun get(id: String): Card?

    @Upsert
    suspend fun upsert(card: Card)

    @Upsert
    suspend fun upsertAll(cards: List<Card>)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM cards")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(cards: List<Card>) {
        deleteAll()
        upsertAll(cards)
    }
}
