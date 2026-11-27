package com.myra.assistant.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Separate event memory for the user's clearly expressed emotional moments.
 * Each entry is grounded in an exact quote from the latest user utterance.
 * It records a moment, not a diagnosis or a claim about the user's current mood.
 */
object EmotionalMemoryToolRegistry {
    private val sequence = AtomicLong(0)
    private val allowedEmotions = setOf(
        "happy", "excited", "proud", "relieved", "grateful", "affectionate",
        "playful", "amused", "sad", "disappointed", "frustrated", "angry",
        "worried", "anxious", "lonely", "confused", "overwhelmed", "calm",
    )
    private val blockedTerms = listOf(
        "otp", "one time password", "password", "passcode", "pin", "cvv",
        "aadhaar", "aadhar", "pan number", "bank account", "account number",
        "credit card", "debit card", "diagnosis", "disease", "medication",
        "religion", "caste", "political party", "sexual orientation",
        "sex life", "sexual activity", "criminal history",
    )

    fun declaration(): JSONObject = JSONObject().apply {
        put("name", "save_emotional_memory")
        put(
            "description",
            "Propose a meaningful emotional moment explicitly expressed by the USER in the latest message for explicit user approval in MYRA Settings; this tool does not itself save the event as approved memory. including happiness, sadness, worry, affection, pride, relief, frustration, laughter or playful humor. Use this for the user's own experience, not another person's feelings. The quote must be copied exactly from the latest user message. Do not infer a mood from a neutral message, sarcasm alone, a hypothetical, a question, a joke about someone else, or ambiguous wording. Save meaningful moments, not every routine greeting. This is a dated event memory, not a diagnosis and not proof of the user's current mood. Never include sensitive personal/medical details.",
        )
        put(
            "parameters",
            JSONObject().apply {
                put("type", "OBJECT")
                put(
                    "properties",
                    JSONObject().apply {
                        put("emotion", JSONObject().put("type", "STRING").put("description", "One of: ${allowedEmotions.sorted().joinToString(", ")}"))
                        put("context", JSONObject().put("type", "STRING").put("description", "Brief, non-sensitive reason or situation, preserving only what the user actually said"))
                        put("evidence", JSONObject().put("type", "STRING").put("description", "Exact short quote from the user's latest message; do not paraphrase"))
                        put("confidence", JSONObject().put("type", "STRING").put("description", "Confidence from 0.00 to 1.00; save only at 0.85 or higher"))
                    },
                )
                put("required", JSONArray(listOf("emotion", "context", "evidence", "confidence")))
            },
        )
    }

    fun validate(args: JSONObject, latestUserText: String): MemoryCandidate? {
        val keys = mutableSetOf<String>()
        val iterator = args.keys()
        while (iterator.hasNext()) keys += iterator.next()
        if (keys != setOf("emotion", "context", "evidence", "confidence")) return null

        val emotion = args.optString("emotion").trim().lowercase(Locale.ROOT)
        val context = args.optString("context").trim()
        val evidence = args.optString("evidence").trim()
        val confidence = args.optString("confidence").toFloatOrNull() ?: return null
        if (emotion !in allowedEmotions || context.length !in 2..180 || evidence.length !in 4..240) return null
        if (!confidence.isFinite() || confidence !in 0.85f..1.0f) return null
        if (containsBlockedContent(context) || containsBlockedContent(evidence)) return null
        if (Regex("\\b\\d{4,8}\\b").containsMatchIn(evidence)) return null

        val normalizedMessage = normalize(latestUserText)
        val normalizedEvidence = normalize(evidence)
        if (normalizedMessage.isBlank() || normalizedEvidence.length < 4 ||
            !normalizedMessage.contains(normalizedEvidence)
        ) return null

        val lower = evidence.lowercase(Locale.ROOT)
        if (evidence.contains("?") || evidence.contains("क्या") ||
            listOf("what if", "suppose", "maybe", "perhaps", "शायद", "अगर", "मान लो", "काश").any {
                lower.contains(it)
            }
        ) return null

        // Avoid treating a third party's mood as the user's own emotional event.
        val firstPersonCue = listOf(
            "i ", "i'm", "im ", "me ", "i feel", "i am", "main ", "mai ", "mae ", "mujhe", "mujh", "mera mood", "meri feeling", "my mood", "my feelings", "my day",
            "मुझे", "मैं", "मै", "मुझे लगा", "मेरा मन",
            "आज मैं", "आज मुझे", "मुझे बहुत", "मेरे साथ", "मेरा मूड", "मेरी भावना", "मेरी फीलिंग", "मेरी खुशी", "मेरा दुख",
        ).any { lower.contains(it) }
        if (!firstPersonCue) return null

        val eventId = "${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        return MemoryCandidate(
            category = "emotion",
            key = "emotion:$eventId",
            value = "emotion=$emotion; context=$context",
            sourceText = evidence.take(240),
            confidence = confidence,
        )
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
