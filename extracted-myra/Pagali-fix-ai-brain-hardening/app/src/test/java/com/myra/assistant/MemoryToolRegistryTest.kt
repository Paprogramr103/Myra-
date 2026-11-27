package com.myra.assistant

import com.myra.assistant.ai.MemoryToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryToolRegistryTest {
    @Test
    fun acceptsSemanticFactWhenEvidenceIsAnExactUserQuote() {
        val args = JSONObject()
            .put("category", "preference")
            .put("value", "Prefers tea every morning")
            .put("evidence", "मुझे हर सुबह चाय पीना पसंद है")
            .put("confidence", "0.94")

        val candidate = MemoryToolRegistry.validate(
            args,
            "मुझे हर सुबह चाय पीना पसंद है, इससे मेरा दिन अच्छा शुरू होता है।",
        )

        assertNotNull(candidate)
        assertEquals("preference", candidate?.category)
        assertEquals("Prefers tea every morning", candidate?.value)
        assertEquals("preference:prefers-tea-every-morning", candidate?.key)

        val shopping = JSONObject()
            .put("category", "preference")
            .put("value", "Enjoys shopping for books")
            .put("evidence", "I enjoy shopping for books")
            .put("confidence", "0.91")
        assertNotNull(MemoryToolRegistry.validate(shopping, "I enjoy shopping for books"))
    }

    @Test
    fun rejectsParaphrasedOrInventedEvidence() {
        val args = JSONObject()
            .put("category", "goal")
            .put("value", "Wants to become a teacher")
            .put("evidence", "I want to become a teacher")
            .put("confidence", "0.98")

        assertNull(MemoryToolRegistry.validate(args, "I am thinking about different careers."))
    }

    @Test
    fun rejectsLowConfidenceSensitiveAndQuestionContent() {
        val base = JSONObject()
            .put("category", "preference")
            .put("value", "Likes tea")
            .put("evidence", "I like tea")
            .put("confidence", "0.70")
        assertNull(MemoryToolRegistry.validate(base, "I like tea"))

        val sensitive = JSONObject()
            .put("category", "other")
            .put("value", "Bank OTP 123456")
            .put("evidence", "My bank OTP is 123456")
            .put("confidence", "0.99")
        assertNull(MemoryToolRegistry.validate(sensitive, "My bank OTP is 123456"))

        val question = JSONObject()
            .put("category", "goal")
            .put("value", "Wants to move to Delhi")
            .put("evidence", "What if I move to Delhi?")
            .put("confidence", "0.99")
        assertNull(MemoryToolRegistry.validate(question, "What if I move to Delhi?"))
    }

    @Test
    fun createsFunctionDeclarationWithRequiredEvidenceFields() {
        val declaration = MemoryToolRegistry.declaration()
        assertEquals("save_user_memory", declaration.getString("name"))
        val schema = declaration.getJSONObject("parameters")
        val properties = schema.getJSONObject("properties")
        assertTrue(properties.has("category"))
        assertTrue(properties.has("value"))
        assertTrue(properties.has("evidence"))
        assertTrue(properties.has("confidence"))
        assertEquals(4, schema.getJSONArray("required").length())
    }
}
