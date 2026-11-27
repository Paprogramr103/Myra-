package com.myra.assistant

import com.myra.assistant.ai.WhatsAppConfirmationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppConfirmationPolicyTest {
    @Test fun hindiConfirmationWithPrefixIsAccepted() {
        assertTrue(WhatsAppConfirmationPolicy.isAffirmative("हाँ भेज दो"))
        assertTrue(WhatsAppConfirmationPolicy.isAffirmative("हाँ, भेज दो"))
        assertTrue(WhatsAppConfirmationPolicy.isAffirmative("जी हाँ भेज दीजिए"))
    }
    @Test fun englishAndHinglishConfirmationIsAccepted() {
        assertTrue(WhatsAppConfirmationPolicy.isAffirmative("haan bhej do"))
        assertTrue(WhatsAppConfirmationPolicy.isAffirmative("yes send it"))
        assertTrue(WhatsAppConfirmationPolicy.isAffirmative("theek hai"))
    }
    @Test fun refusalTakesPrecedenceOverConfirmation() {
        assertTrue(WhatsAppConfirmationPolicy.isNegative("हाँ नहीं भेजना"))
        assertTrue(WhatsAppConfirmationPolicy.isNegative("मत भेजो"))
        assertFalse(WhatsAppConfirmationPolicy.isAffirmative("नहीं भेजो"))
    }
    @Test fun unrelatedSpeechIsNotConfirmation() {
        assertFalse(WhatsAppConfirmationPolicy.isAffirmative("शायद बाद में"))
        assertFalse(WhatsAppConfirmationPolicy.isAffirmative("उसको अभी मत बताना"))
    }
}
