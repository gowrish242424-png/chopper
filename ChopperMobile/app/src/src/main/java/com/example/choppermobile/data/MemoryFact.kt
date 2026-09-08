package com.example.choppermobile.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "memory_facts",
    indices = [
        Index(
            value = ["memoryKey"],
            unique = true
        )
    ]
)
data class MemoryFact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memoryKey: String,
    val memoryValue: String,
    val updatedAt: Long = System.currentTimeMillis()
)