package com.example.choppermobile.memory

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

enum class CommandAction {
    CHAT,
    WEB_SEARCH,
    SAVE_MEMORY,
    RECALL_MEMORY,
    DELETE_MEMORY,
    SECURITY_STATUS,
    SECURITY_HISTORY
}

data class CommandDecision(
    val action: CommandAction,
    val canonicalKey: String = "",
    val value: String = "",
    val lifetime: MemoryLifetime = MemoryLifetime.TEMPORARY,
    val expiresAt: Long = 0L
)

class SemanticCommandInterpreter {

    fun interpret(
        message: String,
        localModel: LlmInference?
    ): CommandDecision {
        if (localModel != null) {
            try {
                val decision = interpretWithLocalAi(message, localModel)
                if (decision != null) return decision
            } catch (_: Exception) {
                // The private local fallback below is used if model JSON is invalid.
            }
        }

        return privateFallback(message)
    }

    private fun interpretWithLocalAi(
        message: String,
        localModel: LlmInference
    ): CommandDecision? {
        val today = LocalDate.now().toString()
        val prompt = """
<start_of_turn>user
Classify a command for a private assistant. Work locally and output JSON only.

Actions:
CHAT, WEB_SEARCH, SAVE_MEMORY, RECALL_MEMORY, DELETE_MEMORY, SECURITY_STATUS, SECURITY_HISTORY

Rules:
- Current facts, news, weather, prices and internet lookup are WEB_SEARCH.
- A personal fact supplied by the user is SAVE_MEMORY.
- A question asking for a saved personal fact is RECALL_MEMORY.
- A request to forget personal information is DELETE_MEMORY.
- Questions about Chopper security being armed or its status are SECURITY_STATUS.
- Questions about security alerts, last security event, or security history are SECURITY_HISTORY.
- Stable identity facts such as name, date of birth, blood group and long-term preferences are PERMANENT.
- Events, plans, current location and short-term tasks are TEMPORARY.
- Date of birth uses canonical key date_of_birth.
- Questions about the user's age also use date_of_birth so age can be calculated.
- Different phrases with the same meaning must use the same snake_case canonical key.
- For temporary facts, expires_at is an epoch-millisecond time after the event when known, otherwise 0.
- Never answer the command.

Today: $today
Message: $message

JSON schema:
{"action":"CHAT","canonical_key":"","value":"","memory_type":"TEMPORARY","expires_at":0}
<end_of_turn>
<start_of_turn>model
""".trimIndent()

        val output = localModel.generateResponse(prompt)
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        val json = JSONObject(output.substring(start, end + 1))
        val action = CommandAction.valueOf(
            json.getString("action").uppercase()
        )

        val lifetime = runCatching {
            MemoryLifetime.valueOf(
                json.optString("memory_type", "TEMPORARY").uppercase()
            )
        }.getOrDefault(MemoryLifetime.TEMPORARY)

        return CommandDecision(
            action = action,
            canonicalKey = normalizeKey(json.optString("canonical_key")),
            value = json.optString("value").trim().take(300),
            lifetime = lifetime,
            expiresAt = json.optLong("expires_at", 0L)
        )
    }

    private fun privateFallback(message: String): CommandDecision {
        val text = message.lowercase().trim()
        val key = canonicalKeyFromMeaning(text)

        if (text.contains("security") || text.contains("unauthorized") || text.contains("anti theft")) {
            return if (text.contains("history") || text.contains("event") || text.contains("alert") || text.contains("last")) CommandDecision(CommandAction.SECURITY_HISTORY)
            else CommandDecision(CommandAction.SECURITY_STATUS)
        }

        if (
            text.startsWith("forget ") ||
            text.startsWith("delete my ") ||
            text.startsWith("remove my ")
        ) {
            return CommandDecision(
                action = CommandAction.DELETE_MEMORY,
                canonicalKey = key
            )
        }

        if (
            text.contains("what is my") ||
            text.contains("what's my") ||
            text.contains("when was i born") ||
            text.contains("when did i born") ||
            text.contains("when did i get born") ||
            text.contains("where do i live") ||
            text.contains("what do you remember about me")
        ) {
            return CommandDecision(
                action = CommandAction.RECALL_MEMORY,
                canonicalKey = key
            )
        }

        val saveRequested =
            text.startsWith("remember ") ||
                    text.startsWith("save ") ||
                    text.contains("my date of birth is") ||
                    text.contains("my dob is") ||
                    text.contains("my name is") ||
                    text.contains("my favourite") ||
                    text.contains("my favorite")

        if (saveRequested) {
            val value = extractFallbackValue(message, key)
            val permanentKeys = setOf(
                "name",
                "first_name",
                "middle_name",
                "last_name",
                "nickname",
                "username",
                "honorific_prefix",
                "honorific_suffix",
                "age",
                "date_of_birth",
                "blood_group",
                "home_address",
                "city",
                "district",
                "state",
                "country",
                "postal_code",
                "phone_number",
                "email_address",
                "organization",
                "job_title",
                "favorite_subject",
                "favorite_programming_language"
            )

            return CommandDecision(
                action = CommandAction.SAVE_MEMORY,
                canonicalKey = key,
                value = value,
                lifetime = if (permanentKeys.contains(key)) {
                    MemoryLifetime.PERMANENT
                } else {
                    MemoryLifetime.TEMPORARY
                }
            )
        }

        val webWords = listOf(
            "latest", "today", "current", "news", "weather",
            "temperature", "price", "search", "internet", "web",
            "forecast", "live", "prime minister", "chief minister",
            "president", "governor", "ceo"
        )

        if (webWords.any { text.contains(it) }) {
            return CommandDecision(CommandAction.WEB_SEARCH)
        }

        return CommandDecision(CommandAction.CHAT)
    }

    private fun canonicalKeyFromMeaning(text: String): String {
        return when {
            text.contains("date of birth") ||
                    Regex("\\bdob\\b").containsMatchIn(text) ||
                    text.contains("born") ||
                    text.contains("birthday") ||
                    text.contains("how old") -> "date_of_birth"

            Regex("\\bmy age\\b").containsMatchIn(text) -> "age"

            text.contains("first name") || text.contains("given name") -> "first_name"
            text.contains("middle name") || text.contains("additional name") -> "middle_name"
            text.contains("last name") || text.contains("family name") ||
                    text.contains("surname") -> "last_name"
            text.contains("nickname") -> "nickname"
            text.contains("username") || text.contains("user name") -> "username"
            text.contains("name") || text.contains("call me") -> "name"
            text.contains("blood group") -> "blood_group"
            text.contains("postal code") || text.contains("postcode") ||
                    text.contains("zip code") -> "postal_code"
            text.contains("country") -> "country"
            text.contains("state") -> "state"
            text.contains("district") -> "district"
            text.contains("city") -> "city"
            text.contains("address") -> "home_address"
            text.contains("phone") || text.contains("mobile number") -> "phone_number"
            text.contains("email") -> "email_address"
            text.contains("job title") || text.contains("organization title") -> "job_title"
            text.contains("organization") || text.contains("company") -> "organization"
            text.contains("programming language") -> "favorite_programming_language"
            text.contains("subject") -> "favorite_subject"
            text.contains("location") || text.contains("where do i live") -> "current_location"
            text.contains("event") -> "event"
            else -> normalizeKey(
                text
                    .removePrefix("remember that ")
                    .removePrefix("remember ")
                    .removePrefix("save ")
                    .removePrefix("forget ")
                    .removePrefix("delete my ")
                    .substringBefore(" is ")
                    .substringBefore(" was ")
                    .removePrefix("my ")
            )
        }
    }

    private fun extractFallbackValue(message: String, key: String): String {
        val separators = listOf(" is ", " as ", ":")
        var value = message.trim()

        for (separator in separators) {
            val index = value.indexOf(separator, ignoreCase = true)
            if (index >= 0) {
                value = value.substring(index + separator.length)
                break
            }
        }

        if (key == "name") {
            value = value
                .replace(Regex("(?i)^my name is\\s+"), "")
                .replace(Regex("(?i)^call me\\s+"), "")
        }

        return value
            .trim()
            .trim('.', ',', '!', '?', ';', ':')
            .take(300)
    }

    private fun normalizeKey(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(60)
    }
}
