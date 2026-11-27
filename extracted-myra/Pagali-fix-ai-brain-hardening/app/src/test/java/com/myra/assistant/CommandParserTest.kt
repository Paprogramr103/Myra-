package com.myra.assistant

import com.myra.assistant.ai.CommandParser
import com.myra.assistant.model.AppCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    @Test
    fun `parses open app in hinglish and english`() {
        val c1 = CommandParser.parse("YouTube kholo")
        assertNotNull(c1)
        assertEquals(AppCommand.OPEN_APP, c1?.type)
        assertEquals("YouTube", c1?.params?.get("app_name"))

        val c2 = CommandParser.parse("open Spotify")
        assertEquals(AppCommand.OPEN_APP, c2?.type)
        assertEquals("Spotify", c2?.params?.get("app_name"))
    }

    @Test
    fun `parses close app and navigation commands`() {
        assertEquals(AppCommand.CLOSE_APP, CommandParser.parse("WhatsApp band karo")?.type)
        assertEquals(AppCommand.CLOSE_APP, CommandParser.parse("close Instagram")?.type)
        assertEquals(AppCommand.GO_BACK, CommandParser.parse("go back")?.type)
        assertEquals(AppCommand.SCROLL_DOWN, CommandParser.parse("scroll down")?.type)
    }

    @Test
    fun `parses calls sms whatsapp and prime contacts`() {
        val prime0 = CommandParser.parse("mere close friend ko call karo")
        assertEquals(AppCommand.PRIME_CALL, prime0?.type)
        assertEquals("0", prime0?.params?.get("index"))

        val prime1 = CommandParser.parse("call my second contact")
        assertEquals(AppCommand.PRIME_CALL, prime1?.type)
        assertEquals("1", prime1?.params?.get("index"))

        val loveMsg = CommandParser.parse("meri jaan ko msg karo")
        assertEquals(AppCommand.PRIME_MSG, loveMsg?.type)

        val call = CommandParser.parse("Rahul ko call karo")
        assertEquals(AppCommand.CALL, call?.type)
        assertEquals("Rahul", call?.params?.get("target"))


        val whatsappCall1 = CommandParser.parse("WhatsApp par papa ko call karo")
        assertEquals(AppCommand.WHATSAPP_CALL, whatsappCall1?.type)
        assertEquals("papa", whatsappCall1?.params?.get("target"))

        val whatsappCall2 = CommandParser.parse("papa ko WhatsApp par voice call karo")
        assertEquals(AppCommand.WHATSAPP_CALL, whatsappCall2?.type)
        assertEquals("papa", whatsappCall2?.params?.get("target"))

        val hindiSimCall = CommandParser.parse("पापा को कॉल करो")
        assertEquals(AppCommand.CALL, hindiSimCall?.type)
        assertEquals("पापा", hindiSimCall?.params?.get("target"))

        val romanHindiSimCall = CommandParser.parse("Hey MYRA, papa ko phone lagao")
        assertEquals(AppCommand.CALL, romanHindiSimCall?.type)
        assertEquals("papa", romanHindiSimCall?.params?.get("target"))

        val sms = CommandParser.parse("SMS bhejo Aman ko Hello bhai")
        assertEquals(AppCommand.SMS, sms?.type)
        assertEquals("Aman", sms?.params?.get("target"))

        val wa = CommandParser.parse("WhatsApp karo Neha ko Hi")
        assertEquals(AppCommand.WHATSAPP_MSG, wa?.type)
        assertEquals("Neha", wa?.params?.get("target"))

        val hindiWhatsApp = CommandParser.parse("पापा को WhatsApp पर मैसेज भेजो कि मैं घर पहुँच गया हूँ")
        assertEquals(AppCommand.WHATSAPP_MSG, hindiWhatsApp?.type)
        assertEquals("पापा", hindiWhatsApp?.params?.get("target"))
        assertEquals("मैं घर पहुँच गया हूँ", hindiWhatsApp?.params?.get("message"))

        val romanWhatsApp = CommandParser.parse("Hey MYRA, papa ko WhatsApp par message bhejo ki main ghar pahunch gaya")
        assertEquals(AppCommand.WHATSAPP_MSG, romanWhatsApp?.type)
        assertEquals("papa", romanWhatsApp?.params?.get("target"))
        assertEquals("main ghar pahunch gaya", romanWhatsApp?.params?.get("message"))

        val englishWhatsApp = CommandParser.parse("send WhatsApp message to Neha saying I will be late")
        assertEquals(AppCommand.WHATSAPP_MSG, englishWhatsApp?.type)
        assertEquals("Neha", englishWhatsApp?.params?.get("target"))
        assertEquals("I will be late", englishWhatsApp?.params?.get("message"))
    }

    @Test
    fun `parses YouTube play requests before generic app launch`() {
        val hinglish = CommandParser.parse("Hey MYRA open YouTube play romantic songs")
        assertEquals(AppCommand.YOUTUBE_SEARCH_PLAY, hinglish?.type)
        assertEquals("romantic songs", hinglish?.params?.get("query"))

        val english = CommandParser.parse("play Arijit Singh songs on YouTube")
        assertEquals(AppCommand.YOUTUBE_SEARCH_PLAY, english?.type)
        assertEquals("Arijit Singh songs", english?.params?.get("query"))

        val hindi = CommandParser.parse("यूट्यूब पर रोमांटिक गाने चलाओ")
        assertEquals(AppCommand.YOUTUBE_SEARCH_PLAY, hindi?.type)
        assertEquals("रोमांटिक गाने", hindi?.params?.get("query"))

        assertEquals(AppCommand.OPEN_APP, CommandParser.parse("open YouTube")?.type)
    }

    @Test
    fun `parses device controls maps parking and scanner`() {
        assertEquals(AppCommand.FLASHLIGHT_ON, CommandParser.parse("torch on karo")?.type)
        assertEquals(AppCommand.FLASHLIGHT_OFF, CommandParser.parse("flashlight off")?.type)
        assertEquals(AppCommand.VOLUME_UP, CommandParser.parse("volume badhao")?.type)
        assertEquals(AppCommand.VOLUME_DOWN, CommandParser.parse("volume down")?.type)
        assertEquals(AppCommand.WIFI_SETTINGS, CommandParser.parse("WiFi on karo")?.type)
        assertEquals(AppCommand.BLUETOOTH_SETTINGS, CommandParser.parse("Bluetooth settings kholo")?.type)

        val nav = CommandParser.parse("navigate to India Gate")
        assertEquals(AppCommand.NAVIGATE_MAPS, nav?.type)
        assertEquals("India Gate", nav?.params?.get("destination"))

        assertEquals(AppCommand.SAVE_PARKING, CommandParser.parse("parking save karo")?.type)
        assertEquals(AppCommand.FIND_PARKING, CommandParser.parse("find my car")?.type)
        assertEquals(AppCommand.SCAN_BARCODE, CommandParser.parse("barcode scan karo")?.type)
        assertEquals(AppCommand.CAMERA_VISION, CommandParser.parse("camera kholo")?.type)
        assertEquals(AppCommand.CAMERA_VISION, CommandParser.parse("take photo")?.type)

        // Native Hindi commands must reach the same structured actions.
        assertEquals(AppCommand.FLASHLIGHT_ON, CommandParser.parse("टॉर्च चालू करो")?.type)
        assertEquals(AppCommand.FLASHLIGHT_OFF, CommandParser.parse("टॉर्च बंद करो")?.type)
        assertEquals(AppCommand.VOLUME_UP, CommandParser.parse("आवाज़ बढ़ाओ")?.type)
        assertEquals(AppCommand.VOLUME_DOWN, CommandParser.parse("आवाज़ कम करो")?.type)
        assertEquals(AppCommand.WIFI_SETTINGS, CommandParser.parse("वाई-फाई सेटिंग खोलो")?.type)
        assertEquals(AppCommand.BLUETOOTH_SETTINGS, CommandParser.parse("ब्लूटूथ सेटिंग खोलो")?.type)
        assertEquals(AppCommand.CAMERA_VISION, CommandParser.parse("कैमरा खोलो")?.type)
        assertEquals(AppCommand.SCAN_BARCODE, CommandParser.parse("क्यूआर कोड स्कैन करो")?.type)
        val hindiRoute = CommandParser.parse("इंडिया गेट का रास्ता बताओ")
        assertEquals(AppCommand.NAVIGATE_MAPS, hindiRoute?.type)
        assertEquals("इंडिया गेट", hindiRoute?.params?.get("destination"))
    }

    @Test
    fun `returns null for normal conversation`() {
        assertNull(CommandParser.parse("Aaj mausam kaisa hai MYRA?"))
        assertNull(CommandParser.parse("Tell me a motivational quote"))
    }
}
