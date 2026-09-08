package com.example.choppermobile.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

@Database(
    entities = [
        Conversation::class,
        ChatMessage::class,
        MemoryFact::class
    ],
    version = 1,
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
                ).build().also {
                    instance = it
                }
            }
        }
    }
}