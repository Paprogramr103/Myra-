package com.myra.assistant.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MemoryCandidate(
    val category: String,
    val key: String,
    val value: String,
    val sourceText: String,
    val confidence: Float,
)

/**
 * Semantic memory tool contract for Gemini Live.
 * The model proposes a memory, but the app independently verifies provenance,
 * confidence, category and sensitive-data exclusions before persistence.
 */
object MemoryToolRegistry {
    private val allowedCategories = setOf(
        "preference", "identity", "relationship", "location", "goal",
        "routine", "work", "education", "project", "accessibility", "device",
    )

    private val blockedTerms = listOf(
        "otp", "one time password", "password", "passcode", "pin", "cvv",
        "aadhaar", "aadhar", "pan number", "bank account", "account number",
        "credit card", "debit card", "medical", "diagnosis", "disease",
        "religion", "caste", "political party", "sexual orientation",
        "sex life", "sexual activity", "criminal history",
    )

    fun declaration(): JSONObject = JSONObject().apply {
        put("name", "save_user_memory")
        put(
            "description",
            "Propose one stable, useful, non-sensitive fact about the USER from the current user message for explicit user approval in MYRA Settings; this tool does not itself save the fact as approved memory. Call proactively when the user clearly states a lasting preference, identity fact, family relationship, location, goal, routine, work/education detail, long-term project or important constraint. Do not save temporary events, one-off requests, guesses, jokes, hypothetical examples, facts about unrelated third parties, or sensitive/private data. The evidence field MUST be an exact short quote copied from the user's own latest message. Use confidence only when at least 0.85.",
        )
        put(
            "parameters",
            JSONObject().apply {
                put("type", "OBJECT")
                put(
                    "properties",
                    JSONObject().apply {
                        put("category", stringProperty("One of: preference, identity, relationship, location, goal, routine, work, education, project, accessibility, device"))
                        put("value", stringProperty("Concise normalized fact, preserving the user's meaning"))
                        put("evidence", stringProperty("Exact short quote from the user's own latest message; do not paraphrase"))
                        put("confidence", stringProperty("Confidence from 0.00 to 1.00; save only at 0.85 or higher"))
                    },
                )
                put("required", JSONArray(listOf("category", "value", "evidence", "confidence")))
            },
        )
    }

    fun validate(args: JSONObject, latestUserText: String): MemoryCandidate? {
        val keys = mutableSetOf<String>()
        val iterator = args.keys()
        while (iterator.hasNext()) keys += iterator.next()
        if (keys != setOf("category", "value", "evidence", "confidence")) return null

        val category = args.optString("category").trim().lowercase(Locale.ROOT)
        val value = args.optString("value").trim()
        val evidence = args.optString("evidence").trim()
        val confidence = args.optString("confidence").toFloatOrNull() ?: return null
        if (category !in allowedCategories || value.length !in 2..160 || evidence.length !in 4..240) return null
        if (!confidence.isFinite() || confidence !in 0.85f..1.0f) return null
        if (containsBlockedContent(value) || containsBlockedContent(evidence)) return null
        if (Regex("\\b\\d{4,8}\\b").containsMatchIn(evidence) ||
            Regex("\\b\\d{12}\\b").containsMatchIn(evidence)
        ) return null

        val normalizedMessage = normalize(latestUserText)
        val normalizedEvidence = normalize(evidence)
        if (normalizedMessage.isBlank() || normalizedEvidence.length < 4 ||
            !normalizedMessage.contains(normalizedEvidence)
        ) return null

        // Never save a question, negation, or hypothetical as a settled personal fact.
        val evidenceLower = evidence.lowercase(Locale.ROOT)
        if (evidence.contains("?") || evidence.contains("क्या") ||
            listOf("maybe", "perhaps", "suppose", "if i", "what if", "शायद", "अगर", "मान लो").any {
                evidenceLower.contains(it)
            }
        ) return null

        val keyValue = when (category) {
            "identity", "location", "goal" -> "primary"
            "relationship" -> relationshipKey(value)
            else -> normalize(value).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(70)
        }
        if (keyValue.isBlank()) return null

        return MemoryCandidate(
            category = category,
            key = "$category:$keyValue",
            value = value,
            sourceText = evidence.take(240),
            confidence = confidence,
        )
    }

    private fun stringProperty(description: String) = JSONObject().apply {
        put("type", "STRING")
        put("description", description)
    }

    private fun relationshipKey(value: String): String {
        val relation = value.substringBefore(':').trim().lowercase(Locale.ROOT)
        return relation.ifBlank { normalize(value).take(50) }
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
    }

    private fun containsBlockedContent(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return blockedTerms.any { term ->
            val escaped = Regex.escape(term)
            Regex("(?<![a-z])$escaped(?![a-z])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
