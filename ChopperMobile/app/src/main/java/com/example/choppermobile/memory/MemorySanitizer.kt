package com.example.choppermobile.memory

class MemorySanitizer {

    private val sensitivePatterns = listOf(
        Regex("(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|bearer|vault|credit[_-]?card|cvv|pin)\\b\\s*[:=]\\s*\\S+"),
        Regex("(?i)\\b(bearer\\s+[a-zA-Z0-9._~+/-]+=*)"),
        Regex("(?i)\\b([a-zA-Z0-9_]{32,})\\b")
    )

    fun sanitize(input: String): String {
        if (input.isBlank()) return ""
        var sanitized = input
        for (pattern in sensitivePatterns) {
            sanitized = pattern.replace(sanitized, "[REDACTED]")
        }
        return sanitized.trim()
    }
}