package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand

/**
 * Fast local Hinglish + English command router.
 * Converts direct voice/text intents into structured AppCommands without waiting for cloud roundtrips.
 */
object CommandParser {

    fun parse(rawInput: String): AppCommand? {
        val text = rawInput.trim().replace(Regex("""^(?:hey\s+myra|hi\s+myra|hello\s+myra|ok\s+myra|suno\s+myra|हे\s*मायरा|सुनो\s*मायरा)[,،\s]*""", RegexOption.IGNORE_CASE), "").trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()

        // 1. Prime Contacts
        if (lower.contains("close friend") || lower.contains("best friend") || lower.contains("first prime")) {
            return if (lower.contains("msg") || lower.contains("message")) {
                AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "0"))
            } else {
                AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))
            }
        }
        if (lower.contains("second contact") || lower.contains("dusre contact") || lower.contains("second prime")) {
            return if (lower.contains("msg") || lower.contains("message")) {
                AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "1"))
            } else {
                AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "1"))
            }
        }
        if (lower.contains("meri jaan") || lower.contains("my love")) {
            return if (lower.contains("call") || lower.contains("phone") || lower.contains("कॉल") || lower.contains("फोन")) {
                AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))
            } else {
                AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "0"))
            }
        }

        // 2. Flashlight / Torch
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light") ||
            lower.contains("light") || lower.contains("टॉर्च") || lower.contains("फ्लैशलाइट") || lower.contains("लाइट")
        ) {
            if (lower.contains("on") || lower.contains("chalu") || lower.contains("jalao") ||
                lower.contains("चालू") || lower.contains("जलाओ") || lower.contains("जला दो") || lower.contains("ऑन")
            ) return AppCommand(AppCommand.FLASHLIGHT_ON)
            if (lower.contains("off") || lower.contains("band") || lower.contains("bujhao") ||
                lower.contains("बंद") || lower.contains("बुझाओ") || lower.contains("बुझा दो") || lower.contains("ऑफ")
            ) return AppCommand(AppCommand.FLASHLIGHT_OFF)
        }

        // 3. Volume
        if (lower.contains("volume") || lower.contains("awaz") || lower.contains("sound") ||
            lower.contains("आवाज़") || lower.contains("आवाज") || lower.contains("ध्वनि")
        ) {
            if (lower.contains("up") || lower.contains("badhao") || lower.contains("increase") ||
                lower.contains("tez") || lower.contains("zyada") || lower.contains("बढ़ाओ") ||
                lower.contains("तेज") || lower.contains("ज़्यादा") || lower.contains("ज्यादा")
            ) return AppCommand(AppCommand.VOLUME_UP)
            if (lower.contains("down") || lower.contains("kam") || lower.contains("decrease") ||
                lower.contains("slow") || lower.contains("कम") || lower.contains("घटाओ") || lower.contains("धीमा")
            ) return AppCommand(AppCommand.VOLUME_DOWN)
        }

        // 4. Wi-Fi & Bluetooth Settings Panels
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("वाई-फाई") || lower.contains("वाईफाई")) {
            if (lower.contains("on") || lower.contains("off") || lower.contains("setting") || lower.contains("kholo") ||
                lower.contains("band") || lower.contains("chalu") || lower.contains("चालू") || lower.contains("बंद") ||
                lower.contains("सेटिंग") || lower.contains("खोलो")
            ) {
                return AppCommand(AppCommand.WIFI_SETTINGS)
            }
        }
        if (lower.contains("bluetooth") || lower.contains("ब्लूटूथ")) {
            if (lower.contains("on") || lower.contains("off") || lower.contains("setting") || lower.contains("kholo") ||
                lower.contains("band") || lower.contains("chalu") || lower.contains("चालू") || lower.contains("बंद") ||
                lower.contains("सेटिंग") || lower.contains("खोलो")
            ) {
                return AppCommand(AppCommand.BLUETOOTH_SETTINGS)
            }
        }

        // 5. Parking Assistance
        if (lower.contains("parking") || lower.contains("parked") || lower.contains("gaadi") || lower.contains("car")) {
            if (lower.contains("save") || lower.contains("remember") || lower.contains("yaad") || lower.contains("mark")) {
                return AppCommand(AppCommand.SAVE_PARKING, mapOf("note" to text))
            }
            if (lower.contains("find") || lower.contains("kahan") || lower.contains("where") || lower.contains("locate")) {
                return AppCommand(AppCommand.FIND_PARKING)
            }
        }

        // 6. Camera Vision
        if (
            lower == "camera" ||
            lower.contains("camera kholo") ||
            lower.contains("open camera") ||
            lower.contains("take photo") ||
            lower.contains("photo lo") ||
            lower.contains("tasveer lo") ||
            lower.contains("capture photo") ||
            lower.contains("camera open") ||
            (lower.contains("कैमरा") && (lower.contains("खोलो") || lower.contains("खोल") || lower.contains("ओपन"))) ||
            lower.contains("फोटो लो") || lower.contains("तस्वीर लो") || lower.contains("फोटो खींचो")
        ) {
            return AppCommand(AppCommand.CAMERA_VISION)
        }

        // 7. Barcode / QR Scanner
        if (lower.contains("barcode") || lower.contains("qr code") || lower.contains("qr scan") || lower.contains("scan code") ||
            lower.contains("बारकोड") || lower.contains("क्यूआर कोड") || lower.contains("क्यूआर स्कैन") ||
            lower.contains("क्यू आर कोड")
        ) {
            return AppCommand(AppCommand.SCAN_BARCODE)
        }

        // 7. Maps Navigation
        Regex("""^(?:navigate|directions|route)\s+(?:to\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val dest = it.groupValues[1].trim()
            if (dest.isNotEmpty()) return AppCommand(AppCommand.NAVIGATE_MAPS, mapOf("destination" to dest))
        }
        Regex("""^(.+?)\s+(?:ka\s+rasta|directions|navigate)\s+(?:dikhao|batao|chalo)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val dest = it.groupValues[1].trim()
            if (dest.isNotEmpty()) return AppCommand(AppCommand.NAVIGATE_MAPS, mapOf("destination" to dest))
        }

        Regex("""^(.+?)\s+का रास्ता\s+(?:दिखाओ|बताओ)$""").find(text)?.let {
            val dest = it.groupValues[1].trim()
            if (dest.isNotEmpty()) return AppCommand(AppCommand.NAVIGATE_MAPS, mapOf("destination" to dest))
        }

        // 8. Accessibility Close / Back / Scroll
        if (lower.contains("band karo") || lower.startsWith("close ")) {
            if (!lower.contains("torch") && !lower.contains("wifi") && !lower.contains("bluetooth")) {
                return AppCommand(AppCommand.CLOSE_APP)
            }
        }
        if (lower == "go back" || lower == "back jao" || lower == "peeche jao") {
            return AppCommand(AppCommand.GO_BACK)
        }
        if (lower.contains("scroll down") || lower.contains("niche scroll")) {
            return AppCommand(AppCommand.SCROLL_DOWN)
        }
        if (lower.contains("scroll up") || lower.contains("upar scroll")) {
            return AppCommand(AppCommand.SCROLL_UP)
        }

        // 9. YouTube media intent must be checked BEFORE generic "open app" parsing.
        val youtubePatterns = listOf(
            Regex("""^(?:open|launch|start)\s+youtube\s+(?:and\s+)?(?:play|search|chalao|bajao)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+(?:par\s+)?(?:play|search|chalao|bajao)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:play|chalao|bajao)\s+(.+?)\s+(?:on\s+youtube|youtube\s+par)$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+(.+?)\s+(?:play|chalao|bajao)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:यूट्यूब|youtube)\s+पर\s+(.+?)\s+(?:चलाओ|बजाओ|प्ले करो)$"""),
            Regex("""^(.+?)\s+(?:यूट्यूब पर|youtube par)\s+(?:चलाओ|बजाओ|play karo)$""", RegexOption.IGNORE_CASE),
        )
        youtubePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.trim() }
            ?.takeIf { it.isNotBlank() }
            ?.let { return AppCommand(AppCommand.YOUTUBE_SEARCH_PLAY, mapOf("query" to it)) }

        // 10. Open App
        Regex("""^(?:open|launch|start)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val appName = it.groupValues[1].trim()
            if (appName.isNotEmpty() && !appName.equals("settings", true)) {
                return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to appName))
            } else if (appName.equals("settings", true)) {
                return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to "settings"))
            }
        }
        Regex("""^(.+?)\s+(?:kholo|open\s+karo|chalu\s+karo)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val appName = it.groupValues[1].trim()
            if (appName.isNotEmpty()) {
                return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to appName))
            }
        }

        // 11. WhatsApp voice calls and messages. Parse this before generic SIM-call rules.
        if (lower.contains("whatsapp") || lower.contains("व्हाट्सऐप")) {
            val whatsappCallPatterns = listOf(
                Regex("""^whatsapp\s+(?:par|pe)\s+(.+?)\s+ko\s+(?:voice\s+)?call\s+(?:karo|lagao|kar\s+do)$""", RegexOption.IGNORE_CASE),
                Regex("""^(.+?)\s+ko\s+whatsapp\s+(?:par\s+)?(?:voice\s+)?call\s+(?:karo|lagao|kar\s+do)$""", RegexOption.IGNORE_CASE),
                Regex("""^(?:call|voice\s+call)\s+(.+?)\s+(?:on|via)\s+whatsapp$""", RegexOption.IGNORE_CASE),
                Regex("""^whatsapp\s+(?:call|voice\s+call)\s+(.+)$""", RegexOption.IGNORE_CASE),
                Regex("""^व्हाट्सऐप\s+पर\s+(.+?)\s+को\s+कॉल\s+करो$"""),
            )
            whatsappCallPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.trim() }
                ?.takeIf { it.isNotBlank() }
                ?.let { return AppCommand(AppCommand.WHATSAPP_CALL, mapOf("target" to it)) }

            if (text.contains("को") && text.contains("मैसेज") && text.contains("भेजो") && text.contains("कि")) {
                val target = text.substringBefore("को").trim()
                val message = text.substringAfter("कि").trim()
                if (target.isNotBlank() && message.isNotBlank()) {
                    return AppCommand(AppCommand.WHATSAPP_MSG, mapOf("target" to target, "message" to message))
                }
            }

            val whatsappMessagePatterns = listOf(
                Regex("""^(.+?)\s+ko\s+(?:whatsapp|व्हाट्सऐप)\s+(?:par|pe|पर)\s+(?:message|msg|मैसेज)\s+(?:bhejo|bhej\s+do|send\s+karo|send\s+kar\s+do)\s+(?:ki\s+)?(.+)$""", RegexOption.IGNORE_CASE),
                Regex("""^(?:whatsapp|व्हाट्सऐप)\s+(?:par|pe|पर)\s+(.+?)\s+ko\s+(?:message|msg|मैसेज)\s+(?:bhejo|bhej\s+do|send\s+karo|send\s+kar\s+do)\s+(?:ki\s+)?(.+)$""", RegexOption.IGNORE_CASE),
                Regex("""^(.+?)\s+को\s+(?:व्हाट्सऐप|whatsapp)\s+पर\s+मैसेज\s+भेजो\s+कि\s+(.+)$"""),
                Regex("""^(?:send\s+)?whatsapp\s+(?:message|msg)\s+to\s+(.+?)\s+(?:saying|that)\s+(.+)$""", RegexOption.IGNORE_CASE),
                Regex("""^whatsapp\s+(?:karo|message|msg)\s+(.+?)\s+ko\s+(.+)$""", RegexOption.IGNORE_CASE),
            )
            whatsappMessagePatterns.firstNotNullOfOrNull { it.find(text) }?.let { match ->
                val target = match.groupValues[1].trim()
                val message = match.groupValues[2].trim()
                if (target.isNotBlank() && message.isNotBlank()) {
                    return AppCommand(AppCommand.WHATSAPP_MSG, mapOf("target" to target, "message" to message))
                }
            }
        }

        // 11. SMS Draft
        Regex("""(?:sms|message|msg)\s+(?:bhejo|send(?:\s+to)?)\s+(.+?)\s+ko(?:\s+(.+))?$""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                val target = it.groupValues[1].trim()
                val msg = it.groupValues.getOrNull(2)?.trim().orEmpty()
                if (target.isNotEmpty()) return AppCommand(AppCommand.SMS, mapOf("target" to target, "message" to msg))
            }
        Regex("""^(?:send\s+)?sms\s+(?:to\s+)?(.+?)(?:\s+saying\s+(.+))?$""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                val target = it.groupValues[1].trim()
                val msg = it.groupValues.getOrNull(2)?.trim().orEmpty()
                if (target.isNotEmpty()) return AppCommand(AppCommand.SMS, mapOf("target" to target, "message" to msg))
            }

        // 12. SIM Phone Call: Hindi, Roman Hindi and English variants.
        val simCallPatterns = listOf(
            Regex("""^(.+?)\s+ko\s+(?:call|phone)\s+(?:karo|lagao|kar\s+do)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+ko\s+(?:phone\s+)?(?:lagao|milao)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+(?:ko\s+)?call\s+karo$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+को\s+(?:कॉल|फोन)\s+(?:करो|लगाओ|मिला\s+दो)$"""),
            Regex("""^(?:कॉल|फोन)\s+(.+?)\s+को\s+(?:करो|लगाओ)$"""),
            Regex("""^(?:call|dial)\s+(.+)$""", RegexOption.IGNORE_CASE),
        )
        simCallPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.trim() }
            ?.takeIf { it.isNotBlank() }
            ?.let { return AppCommand(AppCommand.CALL, mapOf("target" to it)) }

        // 13. Device Status
        if (lower.contains("battery") || lower.contains("ram usage") || lower.contains("device status")) {
            return AppCommand(AppCommand.DEVICE_STATUS)
        }

        return null
    }

    private fun extractTargetAfterKeywords(text: String, keywords: List<String>): String {
        var cleaned = text
        keywords.forEach { kw ->
            cleaned = cleaned.replace(Regex("\\b$kw\\b", RegexOption.IGNORE_CASE), " ")
        }
        return cleaned.trim().replace(Regex("\\s+"), " ")
    }
}
