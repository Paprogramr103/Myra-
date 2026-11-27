package com.myra.assistant.ai

/**
 * Conservative, language-aware confirmation matching for irreversible WhatsApp sends.
 * Negative phrases always take precedence over affirmative phrases.
 */
object WhatsAppConfirmationPolicy {
    private val negativePhrases = listOf(
        "nahi", "नहीं", "ना भेजो", "न भेजो", "मत भेजो", "mat bhejo", "मत भेजना", "mat bhejna",
        "अभी मत भेजो", "nahi bhejna", "no", "not now", "dont send", "don't send",
        "do not send", "never mind", "cancel", "रद्द", "रोक दो", "stop", "बदल दो", "badal do", "change it",
    )
    private val affirmativePhrases = listOf(
        "haan", "हाँ", "जी हाँ", "जी हां", "yes", "okay", "ok", "ठीक है",
        "theek hai", "बिल्कुल", "bilkul", "send kar do", "bhej do", "भेज दो",
        "सेंड कर दो", "हाँ भेज दो", "हां भेज दो", "भेज दीजिए", "भेज देना",
    )
    fun isNegative(spokenText: String): Boolean {
        val padded = " " + normalize(spokenText) + " "
        return negativePhrases.any { phrase -> padded.contains(" " + normalize(phrase) + " ") }
    }
    fun isAffirmative(spokenText: String): Boolean {
        val padded = " " + normalize(spokenText) + " "
        return affirmativePhrases.any { phrase -> padded.contains(" " + normalize(phrase) + " ") }
    }
    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("""[,.!?।؛:]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
