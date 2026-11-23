package com.senjumomo.cards.packs

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Pack-opening history: every simulated pack and what was pulled. Kept in its own database, apart
 * from the real collection, so the simulator can never touch real cards (and no migration of the
 * collection's database is needed).
 */
@Entity(tableName = "openings")
data class PackOpening(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setCode: String,
    val setName: String,
    val openedAt: Long,
    val currency: String,
    /** What the pulls were worth when opened (null if prices were unavailable). */
    val valueCents: Long?,
    /** What an average pack of this set is worth (from simulating many packs). */
    val expectedValueCents: Long?,
    /** Average number of Ultra Rare-or-better cards in a pack of this set. */
    val expectedUltra: Double,
    /** Average number of Secret Rare-or-better cards in a pack of this set. */
    val expectedSecret: Double,
)

@Entity(tableName = "pulls", indices = [Index("openingId"), Index("setName")])
data class Pull(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val openingId: Long,
    val setName: String,
    val code: String,
    val name: String,
    val rarity: String,
    /** [Tier] ordinal. */
    val tier: Int,
    val artworkId: Long,
    val valueCents: Long?,
    val pendulum: Boolean,
)

@Dao
interface PullDao {
    @Insert suspend fun insertOpening(o: PackOpening): Long
    @Insert suspend fun insertPulls(p: List<Pull>)

    @Transaction
    suspend fun record(opening: PackOpening, pulls: List<Pull>) {
        val id = insertOpening(opening)
        insertPulls(pulls.map { it.copy(openingId = id) })
    }

    @Query("SELECT * FROM openings") fun observeOpenings(): Flow<List<PackOpening>>
    @Query("SELECT * FROM pulls") fun observePulls(): Flow<List<Pull>>
    @Query("SELECT * FROM pulls WHERE setName = :setName COLLATE NOCASE") suspend fun pullsFrom(setName: String): List<Pull>
}

@Database(entities = [PackOpening::class, Pull::class], version = 1, exportSchema = false)
abstract class PullDatabase : RoomDatabase() {
    abstract fun pulls(): PullDao

    companion object {
        @Volatile private var instance: PullDatabase? = null

        fun get(context: Context): PullDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PullDatabase::class.java, "pulls.db")
                .build().also { instance = it }
        }
    }
}
