package com.myra.assistant.security

/**
 * Local-only OTP detector and redactor.
 * Ensures sensitive OTP codes are NEVER logged, persisted to Room, sent to Gemini, or forwarded to PC/MYRA Connect.
 */
object OtpSanitizer {
    private val englishOtpKeywordRegex = Regex(
        """(?i)\b(otp|one[- ]time(?:\s*password)?|verification(?:\s*code)?|verify|passcode|pin|upi\s*pin|2fa|auth(?:entication)?\s*code|login\s*code|security\s*code|confirm(?:ation)?\s*code|cvv|mpin|txn\s*password|secret\s*(?:code|pin))\b"""
    )
    private val hindiOtpKeywordRegex = Regex(
        """(ओटीपी|वन[- ]?टाइम(?:\s*पासवर्ड)?|वेरिफिकेशन\s*कोड|सत्यापन\s*कोड|पासकोड|पिन)"""
    )
    private val codeRegex = Regex("""(?<![0-9०-९])([0-9०-९]{4,8})(?![0-9०-९])""")

    fun containsOtp(text: String): Boolean {
        if (text.isBlank()) return false
        return (englishOtpKeywordRegex.containsMatchIn(text) || hindiOtpKeywordRegex.containsMatchIn(text)) && codeRegex.containsMatchIn(text)
    }

    fun extractOtpCode(text: String): String? {
        if (!containsOtp(text)) return null
        return codeRegex.find(text)?.groupValues?.getOrNull(1)?.map { digit ->
            if (digit in '०'..'९') ('0'.code + (digit.code - '०'.code)).toChar() else digit
        }?.joinToString("")
    }

    fun redactIfSensitive(text: String): String {
        if (!containsOtp(text)) return text
        return "[REDACTED_OTP_CONTENT]"
    }
}
