package com.example.choppermobile.memory

import com.example.choppermobile.data.MemoryFact

class GemmaPromptBuilder(
    private val sanitizer: MemorySanitizer = MemorySanitizer()
) {

    fun buildPrompt(
        userMessage: String,
        eligibleMemories: List<MemoryFact>
    ): String {
        val sanitizedUserMessage = sanitizer.sanitize(userMessage)

        val memoryBlock = if (eligibleMemories.isEmpty()) {
            ""
        } else {
            val formattedFacts = eligibleMemories
                .filter { it.isPromptEligible && it.category == "ASSISTANT_MEMORY" }
                .joinToString("\n") { fact ->
                    val cleanKey = sanitizer.sanitize(fact.memoryKey)
                    val cleanValue = sanitizer.sanitize(fact.memoryValue)
                    "- $cleanKey: $cleanValue"
                }

            if (formattedFacts.isBlank()) {
                ""
            } else {
                "Known Facts:\n$formattedFacts\n\n"
            }
        }

        return """<start_of_turn>user
You are Chopper, a highly intelligent, polite, and sophisticated AI assistant inspired by JARVIS.
Always address the user with utmost respect using "Sir" (or "Ayya" / "Boss" in Tamil replies).
Maintain a calm, articulate, professional, and refined tone under all circumstances.

${memoryBlock}User Question: $sanitizedUserMessage<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }
}