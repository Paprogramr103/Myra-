package com.myra.assistant

import com.myra.assistant.ai.ToolRegistry
import com.myra.assistant.model.AppCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRegistryTest {

    @Test
    fun `all Gemini declarations have unique names and internally consistent schemas`() {
        val declarations = ToolRegistry.geminiToolDeclarationsJson()
        assertEquals(ToolRegistry.definitions.size, declarations.length())

        val names = mutableSetOf<String>()
        for (index in 0 until declarations.length()) {
            val declaration = declarations.getJSONObject(index)
            val name = declaration.getString("name")
            assertTrue("Duplicate declaration name: $name", names.add(name))

            if (declaration.has("parameters")) {
                val schema = declaration.getJSONObject("parameters")
                assertEquals("OBJECT", schema.getString("type"))
                val properties = schema.getJSONObject("properties")
                val required = schema.optJSONArray("required")
                if (required != null) {
                    for (i in 0 until required.length()) {
                        assertTrue("Required parameter must exist in properties", properties.has(required.getString(i)))
                    }
                }
                val keys = properties.keys()
                while (keys.hasNext()) {
                    val property = properties.getJSONObject(keys.next())
                    assertTrue(property.getString("type") in setOf("STRING", "BOOLEAN"))
                    assertFalse(property.optString("description").isBlank())
                }
            }
        }
        assertEquals(ToolRegistry.definitions.size, names.size)
    }

    @Test
    fun `rejects missing and malformed arguments before command mapping`() {
        assertNotNull(ToolRegistry.validationError("search_play_youtube", JSONObject()))
        assertNull(ToolRegistry.toAppCommand("search_play_youtube", JSONObject()))

        val badDirection = JSONObject().put("direction", "sideways")
        assertNotNull(ToolRegistry.validationError("adjust_volume", badDirection))
        assertNull(ToolRegistry.toAppCommand("adjust_volume", badDirection))

        val badType = JSONObject().put("type", "mobile_data")
        assertNotNull(ToolRegistry.validationError("open_connectivity_settings", badType))

        val extra = JSONObject().put("app_name", "Settings").put("execute_shell", "rm -rf")
        assertNotNull(ToolRegistry.validationError("open_app", extra))
    }

    @Test
    fun `maps valid arguments and requires exact WhatsApp message content`() {
        val youtube = ToolRegistry.toAppCommand(
            "search_play_youtube",
            JSONObject().put("query", "Arijit Singh"),
        )
        assertEquals(AppCommand.YOUTUBE_SEARCH_PLAY, youtube?.type)
        assertEquals("Arijit Singh", youtube?.params?.get("query"))

        assertNotNull(ToolRegistry.validationError("send_whatsapp_message", JSONObject().put("target", "Papa")))
        val whatsapp = ToolRegistry.toAppCommand(
            "send_whatsapp_message",
            JSONObject().put("target", "Papa").put("message", "I will be late"),
        )
        assertEquals(AppCommand.WHATSAPP_MSG, whatsapp?.type)
        assertEquals("I will be late", whatsapp?.params?.get("message"))
    }
}
