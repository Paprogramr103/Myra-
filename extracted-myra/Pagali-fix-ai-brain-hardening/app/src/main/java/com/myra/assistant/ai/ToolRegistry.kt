package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import org.json.JSONArray
import org.json.JSONObject

data class ToolDefinition(
    val name: String,
    val description: String,
    val commandType: String,
    val parameters: Map<String, Pair<String, String>> = emptyMap(), // paramName -> (type, description)
    val requiredParams: List<String> = emptyList(),
)

/**
 * Central Tool Registry used both for Gemini Live Function Calling declarations
 * and for mapping function calls into native AppCommand execution.
 */
object ToolRegistry {

    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "search_play_youtube",
            description = "Play the exact song or video requested. Pass the song title and artist as the search query, excluding voice-command filler such as 'play', 'gana chalao', or 'bajao'. Open YouTube search, use MYRA Accessibility Service to select only a sufficiently matching visible result, then verify the watch page/player and start playback only through an explicit Play control if paused. Never tap an unrelated result or claim playback started without observing a Play/Pause player state.",
            commandType = AppCommand.YOUTUBE_SEARCH_PLAY,
            parameters = mapOf("query" to ("STRING" to "Exact song, artist, or video search query")),
            requiredParams = listOf("query"),
        ),
        ToolDefinition(
            name = "open_app",
            description = "Open an installed Android application by its name (e.g. YouTube, WhatsApp, Maps, Spotify).",
            commandType = AppCommand.OPEN_APP,
            parameters = mapOf("app_name" to ("STRING" to "Name of the app to open")),
            requiredParams = listOf("app_name"),
        ),
        ToolDefinition(
            name = "close_app",
            description = "Close the current foreground application by returning to the home screen via Accessibility Service.",
            commandType = AppCommand.CLOSE_APP,
        ),
        ToolDefinition(
            name = "make_call",
            description = "Call a contact by name or phone number.",
            commandType = AppCommand.CALL,
            parameters = mapOf("target" to ("STRING" to "Contact name or phone number")),
            requiredParams = listOf("target"),
        ),
        ToolDefinition(
            name = "make_whatsapp_voice_call",
            description = "Open the WhatsApp chat for a contact and hand off to the user to confirm/start the voice call. WhatsApp does not expose a supported public API for silently placing a call.",
            commandType = AppCommand.WHATSAPP_CALL,
            parameters = mapOf("target" to ("STRING" to "WhatsApp contact name or phone number")),
            requiredParams = listOf("target"),
        ),
        ToolDefinition(
            name = "call_prime_contact",
            description = "Call one of the user's saved Prime Contacts (0 for first/close friend, 1 for second, etc.).",
            commandType = AppCommand.PRIME_CALL,
            parameters = mapOf("index" to ("STRING" to "Zero-based index as string, default 0")),
        ),
        ToolDefinition(
            name = "draft_sms",
            description = "Open an SMS draft to a contact or phone number with a message body.",
            commandType = AppCommand.SMS,
            parameters = mapOf(
                "target" to ("STRING" to "Recipient contact name or number"),
                "message" to ("STRING" to "Text message content"),
            ),
            requiredParams = listOf("target"),
        ),
        ToolDefinition(
            name = "send_whatsapp_message",
            description = "Stage the exact requested WhatsApp message for MYRA's native one-time confirmation flow. Do not ask the user for confirmation yourself and do not invoke this tool repeatedly for the same message. MYRA will read the message aloud and ask once; only after a clear explicit yes will the native executor open the WhatsApp chat and may tap Send if Accessibility verifies the foreground WhatsApp package and exact composer text.",
            commandType = AppCommand.WHATSAPP_MSG,
            parameters = mapOf(
                "target" to ("STRING" to "Contact name or phone number"),
                "message" to ("STRING" to "Exact message text to prepare after user confirmation"),
            ),
            requiredParams = listOf("target", "message"),
        ),
        ToolDefinition(
            name = "set_flashlight",
            description = "Turn the device camera flashlight/torch on or off.",
            commandType = AppCommand.FLASHLIGHT_ON,
            parameters = mapOf("enabled" to ("BOOLEAN" to "true to turn on, false to turn off")),
            requiredParams = listOf("enabled"),
        ),
        ToolDefinition(
            name = "adjust_volume",
            description = "Increase or decrease device media/assistant volume.",
            commandType = AppCommand.VOLUME_UP,
            parameters = mapOf("direction" to ("STRING" to "'up' or 'down'")),
            requiredParams = listOf("direction"),
        ),
        ToolDefinition(
            name = "open_connectivity_settings",
            description = "Open Android Wi-Fi or Bluetooth settings panel so user can toggle connectivity.",
            commandType = AppCommand.WIFI_SETTINGS,
            parameters = mapOf("type" to ("STRING" to "'wifi' or 'bluetooth'")),
            requiredParams = listOf("type"),
        ),
        ToolDefinition(
            name = "navigate_maps",
            description = "Launch Google Maps navigation to a destination.",
            commandType = AppCommand.NAVIGATE_MAPS,
            parameters = mapOf("destination" to ("STRING" to "Place or address to navigate to")),
            requiredParams = listOf("destination"),
        ),
        ToolDefinition(
            name = "save_parking_spot",
            description = "Save the user's current GPS location as their parked vehicle spot.",
            commandType = AppCommand.SAVE_PARKING,
            parameters = mapOf("note" to ("STRING" to "Optional parking floor/pillar note")),
        ),
        ToolDefinition(
            name = "find_parking_spot",
            description = "Open walking navigation in Maps to the user's last saved parking spot.",
            commandType = AppCommand.FIND_PARKING,
        ),
        ToolDefinition(
            name = "open_camera_vision",
            description = "Open MYRA's in-app CameraX visual capture screen and analyze a user-approved photo with Gemini Live.",
            commandType = AppCommand.CAMERA_VISION,
        ),
        ToolDefinition(
            name = "open_barcode_scanner",
            description = "Open MYRA's in-app CameraX barcode and QR code scanner.",
            commandType = AppCommand.SCAN_BARCODE,
        ),
        ToolDefinition(
            name = "get_device_status",
            description = "Get battery percentage, RAM usage, and current time.",
            commandType = AppCommand.DEVICE_STATUS,
        ),
        ToolDefinition(
            name = "send_to_pc_companion",
            description = "Send a non-sensitive command or note to a paired PC Companion session.",
            commandType = AppCommand.SEND_TO_PC,
            parameters = mapOf("payload" to ("STRING" to "Message or command to forward to paired PC")),
            requiredParams = listOf("payload"),
        ),
    )

    fun geminiToolDeclarationsJson(): JSONArray {
        val array = JSONArray()
        definitions.forEach { tool ->
            val propsObj = JSONObject()
            tool.parameters.forEach { (paramName, typeAndDesc) ->
                propsObj.put(
                    paramName,
                    JSONObject().apply {
                        put("type", typeAndDesc.first)
                        put("description", typeAndDesc.second)
                    },
                )
            }
            val fn = JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                if (tool.parameters.isNotEmpty()) {
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", propsObj)
                            if (tool.requiredParams.isNotEmpty()) {
                                put("required", JSONArray(tool.requiredParams))
                            }
                        },
                    )
                }
            }
            array.put(fn)
        }
        return array
    }

    /**
     * Validate function-call arguments before allowing any Android side effect.
     * Returns a safe explanation when invalid, or null when the call is valid.
     */
    fun validationError(functionName: String, args: JSONObject): String? {
        val definition = definitions.firstOrNull { it.name == functionName }
            ?: return "Unknown tool: $functionName"

        val suppliedKeys = mutableSetOf<String>()
        val iterator = args.keys()
        while (iterator.hasNext()) suppliedKeys += iterator.next()
        val unexpected = suppliedKeys - definition.parameters.keys
        if (unexpected.isNotEmpty()) {
            return "Unexpected argument(s): ${unexpected.sorted().joinToString()}."
        }

        definition.requiredParams.forEach { key ->
            if (!args.has(key) || args.isNull(key)) return "Missing required argument: $key."
        }
        definition.parameters.forEach { (key, typeAndDescription) ->
            if (!args.has(key) || args.isNull(key)) return@forEach
            val value = args.opt(key)
            when (typeAndDescription.first) {
                "STRING" -> if (value !is String || value.isBlank()) {
                    return "Argument '$key' must be a non-empty string."
                }
                "BOOLEAN" -> if (value !is Boolean) {
                    return "Argument '$key' must be true or false."
                }
                else -> return "Unsupported schema type for '$key'."
            }
        }

        when (functionName) {
            "adjust_volume" -> if (args.optString("direction").lowercase() !in setOf("up", "down")) {
                return "Argument 'direction' must be 'up' or 'down'."
            }
            "open_connectivity_settings" -> if (args.optString("type").lowercase() !in setOf("wifi", "bluetooth")) {
                return "Argument 'type' must be 'wifi' or 'bluetooth'."
            }
            "call_prime_contact" -> if (args.has("index") &&
                args.optString("index").toIntOrNull()?.let { it >= 0 } != true
            ) {
                return "Argument 'index' must be a non-negative integer string."
            }
        }
        return null
    }

    fun toAppCommand(functionName: String, args: JSONObject): AppCommand? {
        if (validationError(functionName, args) != null) return null
        return when (functionName) {
            "search_play_youtube" -> AppCommand(AppCommand.YOUTUBE_SEARCH_PLAY, mapOf("query" to args.getString("query")))
            "open_app" -> AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to args.getString("app_name")))
            "close_app" -> AppCommand(AppCommand.CLOSE_APP)
            "make_call" -> AppCommand(AppCommand.CALL, mapOf("target" to args.getString("target")))
            "make_whatsapp_voice_call" -> AppCommand(AppCommand.WHATSAPP_CALL, mapOf("target" to args.getString("target")))
            "call_prime_contact" -> AppCommand(AppCommand.PRIME_CALL, mapOf("index" to args.optString("index", "0")))
            "draft_sms" -> AppCommand(AppCommand.SMS, mapOf("target" to args.getString("target"), "message" to args.optString("message")))
            "send_whatsapp_message" -> AppCommand(AppCommand.WHATSAPP_MSG, mapOf("target" to args.getString("target"), "message" to args.getString("message")))
            "set_flashlight" -> AppCommand(if (args.getBoolean("enabled")) AppCommand.FLASHLIGHT_ON else AppCommand.FLASHLIGHT_OFF)
            "adjust_volume" -> AppCommand(if (args.getString("direction").lowercase() == "down") AppCommand.VOLUME_DOWN else AppCommand.VOLUME_UP)
            "open_connectivity_settings" -> AppCommand(if (args.getString("type").lowercase() == "bluetooth") AppCommand.BLUETOOTH_SETTINGS else AppCommand.WIFI_SETTINGS)
            "navigate_maps" -> AppCommand(AppCommand.NAVIGATE_MAPS, mapOf("destination" to args.getString("destination")))
            "save_parking_spot" -> AppCommand(AppCommand.SAVE_PARKING, mapOf("note" to args.optString("note")))
            "find_parking_spot" -> AppCommand(AppCommand.FIND_PARKING)
            "open_camera_vision" -> AppCommand(AppCommand.CAMERA_VISION)
            "open_barcode_scanner" -> AppCommand(AppCommand.SCAN_BARCODE)
            "get_device_status" -> AppCommand(AppCommand.DEVICE_STATUS)
            "send_to_pc_companion" -> AppCommand(AppCommand.SEND_TO_PC, mapOf("payload" to args.getString("payload")))
            else -> null
        }
    }
}
