package com.myra.assistant

import com.myra.assistant.service.nextWakeRecognitionLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordLanguageTest {
    @Test
    fun alternatesEnglishAndHindiRecognitionLocales() {
        assertEquals("hi-IN", nextWakeRecognitionLanguage("en-IN"))
        assertEquals("en-IN", nextWakeRecognitionLanguage("hi-IN"))
        assertEquals("en-IN", nextWakeRecognitionLanguage("unknown"))
    }
}
