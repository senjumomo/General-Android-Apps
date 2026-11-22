package com.senjumomo.cards.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Card::class], version = 2, exportSchema = false)
abstract class CardDatabase : RoomDatabase() {
    abstract fun cards(): CardDao

    companion object {
        /** v2: card images and price refresh time. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN imageUrl TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN priceUpdatedAt TEXT")
            }
        }

        @Volatile private var instance: CardDatabase? = null

        fun get(context: Context): CardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CardDatabase::class.java, "cards.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
