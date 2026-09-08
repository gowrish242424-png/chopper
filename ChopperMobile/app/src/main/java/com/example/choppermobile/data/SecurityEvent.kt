package com.example.choppermobile.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "security_events")
data class SecurityEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String,
    val verificationResult: String,
    val qualityResult: String = "NOT_CAPTURED",
    val evidencePath: String = "",
    val sensorSummary: String = "",
    val details: String = ""
)
