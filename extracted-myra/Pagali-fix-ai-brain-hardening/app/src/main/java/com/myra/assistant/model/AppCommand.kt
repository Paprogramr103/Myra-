package com.myra.assistant.model

data class AppCommand(
    val type: String,
    val params: Map<String, String> = emptyMap(),
) {
    companion object {
        const val OPEN_APP = "OPEN_APP"
        const val YOUTUBE_SEARCH_PLAY = "YOUTUBE_SEARCH_PLAY"
        const val CLOSE_APP = "CLOSE_APP"
        const val GO_BACK = "GO_BACK"
        const val CLICK_TEXT = "CLICK_TEXT"
        const val TYPE_TEXT = "TYPE_TEXT"
        const val SCROLL_DOWN = "SCROLL_DOWN"
        const val SCROLL_UP = "SCROLL_UP"
        const val CALL = "CALL"
        const val PRIME_CALL = "PRIME_CALL"
        const val SMS = "SMS"
        const val PRIME_MSG = "PRIME_MSG"
        const val WHATSAPP_MSG = "WHATSAPP_MSG"
        const val WHATSAPP_CALL = "WHATSAPP_CALL"
        const val VOLUME_UP = "VOLUME_UP"
        const val VOLUME_DOWN = "VOLUME_DOWN"
        const val FLASHLIGHT_ON = "FLASHLIGHT_ON"
        const val FLASHLIGHT_OFF = "FLASHLIGHT_OFF"
        const val WIFI_SETTINGS = "WIFI_SETTINGS"
        const val BLUETOOTH_SETTINGS = "BLUETOOTH_SETTINGS"
        const val NAVIGATE_MAPS = "NAVIGATE_MAPS"
        const val GET_CURRENT_LOCATION = "GET_CURRENT_LOCATION"
        const val WEB_SEARCH = "WEB_SEARCH"
        const val GET_WEATHER = "GET_WEATHER"
        const val GET_LATEST_NEWS = "GET_LATEST_NEWS"
        const val SAVE_PARKING = "SAVE_PARKING"
        const val FIND_PARKING = "FIND_PARKING"
        const val SCAN_BARCODE = "SCAN_BARCODE"
        const val CAMERA_VISION = "CAMERA_VISION"
        const val DEVICE_STATUS = "DEVICE_STATUS"
        const val SET_REMINDER = "SET_REMINDER"
        const val SEND_SOS_ALERT = "SEND_SOS_ALERT"
        const val SEND_TO_PC = "SEND_TO_PC"
    }
}
