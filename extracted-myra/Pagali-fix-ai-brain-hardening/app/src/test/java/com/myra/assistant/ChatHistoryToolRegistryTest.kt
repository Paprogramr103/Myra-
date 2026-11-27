package com.myra.assistant

import com.myra.assistant.ai.ChatHistoryToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatHistoryToolRegistryTest {
    @Test
    fun acceptsBoundedHindiAndHinglishSearchVariants() {
        val request = ChatHistoryToolRegistry.validate(
            JSONObject()
                .put("queries", JSONArray(listOf("पापा को कॉल", "papa ko call", "फोन करना")))
                .put("limit", 8),
        )
        assertNotNull(request)
        assertEquals(listOf("पापा को कॉल", "papa ko call", "फोन करना"), request?.queries)
        assertEquals(8, request?.limit)
    }

    @Test
    fun rejectsMissingOrUnexpectedArguments() {
        assertNull(
            ChatHistoryToolRegistry.validate(
                JSONObject().put("queries", JSONArray(listOf("call", "phone"))).put("limit", 5).put("extra", true),
            ),
        )
        assertNull(ChatHistoryToolRegistry.validate(JSONObject().put("queries", JSONArray(listOf("call", "phone")))))
    }

    @Test
    fun rejectsTooFewTooManyOrDuplicateQueries() {
        assertNull(ChatHistoryToolRegistry.validate(JSONObject().put("queries", JSONArray(listOf("call"))).put("limit", 5)))
        assertNull(
            ChatHistoryToolRegistry.validate(
                JSONObject().put("queries", JSONArray(listOf("a1", "a2", "a3", "a4", "a5", "a6"))).put("limit", 5),
            ),
        )
        assertNull(
            ChatHistoryToolRegistry.validate(
                JSONObject().put("queries", JSONArray(listOf("call", "CALL"))).put("limit", 5),
            ),
        )
    }

    @Test
    fun rejectsBlankLongOrMultilineQueries() {
        assertNull(ChatHistoryToolRegistry.validate(JSONObject().put("queries", JSONArray(listOf(" ", "phone"))).put("limit", 5)))
        assertNull(
            ChatHistoryToolRegistry.validate(
                JSONObject().put("queries", JSONArray(listOf("x".repeat(81), "phone"))).put("limit", 5),
            ),
        )
        assertNull(
            ChatHistoryToolRegistry.validate(
                JSONObject().put("queries", JSONArray(listOf("old\nnew", "phone"))).put("limit", 5),
            ),
        )
    }

    @Test
    fun acceptsInclusiveDateBounds() {
        val request = ChatHistoryToolRegistry.validate(
            JSONObject()
                .put("queries", JSONArray(listOf("पापा को कॉल", "papa ko call")))
                .put("limit", 8)
                .put("after_timestamp", 1_700_000_000_000L)
                .put("before_timestamp", 1_800_000_000_000L),
        )
        assertNotNull(request)
        assertEquals(1_700_000_000_000L, request?.afterTimestamp)
        assertEquals(1_800_000_000_000L, request?.beforeTimestamp)
    }

    @Test
    fun rejectsInvalidDateBounds() {
        assertNull(
            ChatHistoryToolRegistry.validate(
                JSONObject()
                    .put("queries", JSONArray(listOf("call", "phone")))
                    .put("limit", 5)
                    .put("after_timestamp", 1_800_000_000_000L)
                    .put("before_timestamp", 1_700_000_000_000L),
            ),
        )
        assertNull(
            ChatHistoryToolRegistry.validate(
                JSONObject()
                    .put("queries", JSONArray(listOf("call", "phone")))
                    .put("limit", 5)
                    .put("after_timestamp", -1L),
            ),
        )
    }

    @Test
    fun rejectsOutOfRangeLimits() {
        assertNull(ChatHistoryToolRegistry.validate(JSONObject().put("queries", JSONArray(listOf("call", "phone"))).put("limit", 0)))
        assertNull(ChatHistoryToolRegistry.validate(JSONObject().put("queries", JSONArray(listOf("call", "phone"))).put("limit", 13)))
    }
}
