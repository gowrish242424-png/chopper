package com.example.choppermobile.security

import com.example.choppermobile.data.SecurityEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityEventTest {
    @Test fun securityEventKeepsVerificationMetadata() {
        val event = SecurityEvent(eventType = "PICKUP_ACCESS", verificationResult = "PENDING_SCAN")
        assertEquals("PICKUP_ACCESS", event.eventType)
        assertEquals("PENDING_SCAN", event.verificationResult)
    }
}
