package com.myra.assistant

import com.myra.assistant.ai.EmotionalMemoryToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmotionalMemoryToolRegistryTest {
    @Test
    fun savesClearlyExpressedHappyAndSadMomentsAsSeparateEvents() {
        val happy = JSONObject()
            .put("emotion", "happy")
            .put("context", "Got good news today")
            .put("evidence", "आज मैं बहुत खुश हूँ")
            .put("confidence", "0.96")
        val sad = JSONObject()
            .put("emotion", "sad")
            .put("context", "Felt hurt by what happened")
            .put("evidence", "मुझे आज बहुत दुख हो रहा है")
            .put("confidence", "0.95")

        val happyMemory = EmotionalMemoryToolRegistry.validate(happy, "आज मैं बहुत खुश हूँ, अच्छी खबर मिली।")
        val sadMemory = EmotionalMemoryToolRegistry.validate(sad, "मुझे आज बहुत दुख हो रहा है।")

        assertNotNull(happyMemory)
        assertNotNull(sadMemory)
        assertEquals("emotion", happyMemory?.category)
        assertEquals("emotion=happy; context=Got good news today", happyMemory?.value)
        assertTrue(happyMemory?.key != sadMemory?.key)
    }

    @Test
    fun rejectsParaphrasedEvidenceHypotheticalsAndThirdPartyEmotion() {
        val base = JSONObject()
            .put("emotion", "sad")
            .put("context", "Felt sad")
            .put("evidence", "I feel sad")
            .put("confidence", "0.99")
        assertNull(EmotionalMemoryToolRegistry.validate(base, "I had a difficult day."))

        val hypothetical = JSONObject(base.toString()).put("evidence", "What if I feel sad?")
        assertNull(EmotionalMemoryToolRegistry.validate(hypothetical, "What if I feel sad?"))

        val thirdParty = JSONObject(base.toString()).put("evidence", "My brother feels sad")
        assertNull(EmotionalMemoryToolRegistry.validate(thirdParty, "My brother feels sad"))
    }

    @Test
    fun rejectsLowConfidenceAndSensitiveDetails() {
        val low = JSONObject()
            .put("emotion", "anxious")
            .put("context", "Feeling worried")
            .put("evidence", "I feel worried")
            .put("confidence", "0.60")
        assertNull(EmotionalMemoryToolRegistry.validate(low, "I feel worried"))

        val sensitive = JSONObject()
            .put("emotion", "sad")
            .put("context", "Medical diagnosis details")
            .put("evidence", "I feel sad")
            .put("confidence", "0.99")
        assertNull(EmotionalMemoryToolRegistry.validate(sensitive, "I feel sad"))
    }

    @Test
    fun declaresDedicatedEmotionalMemoryFunction() {
        val declaration = EmotionalMemoryToolRegistry.declaration()
        assertEquals("save_emotional_memory", declaration.getString("name"))
        val properties = declaration.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(properties.has("emotion"))
        assertTrue(properties.has("context"))
        assertTrue(properties.has("evidence"))
        assertTrue(properties.has("confidence"))
    }
}
