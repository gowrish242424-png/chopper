package com.example.choppermobile.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        Conversation::class,
        ChatMessage::class,
        MemoryFact::class,
        SecurityEvent::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ChopperDatabase : RoomDatabase() {

    abstract fun chopperDao(): ChopperDao

    companion object {

        @Volatile
        private var instance: ChopperDatabase? = null

        fun getInstance(context: Context): ChopperDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChopperDatabase::class.java,
                    "chopper_mobile.db"
                ).addMigrations(object : Migration(1, 2) {
                    override suspend fun migrate(connection: SQLiteConnection) {
                        connection.execSQL("""
                            CREATE TABLE IF NOT EXISTS security_events (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                timestamp INTEGER NOT NULL,
                                eventType TEXT NOT NULL,
                                verificationResult TEXT NOT NULL,
                                qualityResult TEXT NOT NULL,
                                evidencePath TEXT NOT NULL,
                                sensorSummary TEXT NOT NULL,
                                details TEXT NOT NULL
                            )
                        """.trimIndent())
                    }
                }).build().also {
                    instance = it
                }
            }
        }
    }
}
