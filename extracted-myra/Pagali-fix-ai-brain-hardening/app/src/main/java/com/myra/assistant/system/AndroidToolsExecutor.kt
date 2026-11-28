package com.myra.assistant.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.location.Geocoder
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.myra.assistant.data.preferences.SecureSettingsRepository
import com.myra.assistant.data.repository.MyraRepository
import com.myra.assistant.model.AppCommand
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.service.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

data class ToolExecutionResult(
    val success: Boolean,
    val message: String,
    val navigateRoute: String? = null,
    val torchStateChanged: Boolean? = null,
    val forwardToPcPayload: String? = null,
)

class AndroidToolsExecutor(
    private val context: Context,
    private val settingsRepo: SecureSettingsRepository,
    private val myraRepo: MyraRepository,
    private val telemetry: DeviceTelemetryHelper,
) {
    private var torchEnabled = false
    private val webResearch = WebResearchService()

    suspend fun execute(command: AppCommand): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (command.type) {
            AppCommand.OPEN_APP -> openApp(command.params["app_name"].orEmpty())
            AppCommand.YOUTUBE_SEARCH_PLAY -> searchYouTube(command.params["query"].orEmpty())
            AppCommand.CLOSE_APP -> closeCurrentApp()
            AppCommand.GO_BACK -> goBack()
            AppCommand.CLICK_TEXT -> clickText(command.params["text"].orEmpty())
            AppCommand.TYPE_TEXT -> typeText(command.params["text"].orEmpty())
            AppCommand.SCROLL_DOWN -> scroll(forward = true)
            AppCommand.SCROLL_UP -> scroll(forward = false)
            AppCommand.CALL -> makeCall(command.params["target"].orEmpty())
            AppCommand.PRIME_CALL -> callPrimeContact(command.params["index"]?.toIntOrNull() ?: 0)
            AppCommand.SMS -> draftSms(
                target = command.params["target"].orEmpty(),
                message = command.params["message"].orEmpty(),
            )
            AppCommand.PRIME_MSG -> messagePrimeContact(
                index = command.params["index"]?.toIntOrNull() ?: 0,
                message = command.params["message"].orEmpty(),
            )
            AppCommand.WHATSAPP_MSG -> openWhatsApp(
                target = command.params["target"].orEmpty(),
                message = command.params["message"].orEmpty(),
            )
            AppCommand.WHATSAPP_CALL -> openWhatsApp(
                target = command.params["target"].orEmpty(),
                message = "",
                voiceCallHandoff = true,
            )
            AppCommand.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            AppCommand.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            AppCommand.FLASHLIGHT_ON -> setFlashlight(true)
            AppCommand.FLASHLIGHT_OFF -> setFlashlight(false)
            AppCommand.WIFI_SETTINGS -> openWifiPanel()
            AppCommand.BLUETOOTH_SETTINGS -> openBluetoothSettings()
            AppCommand.NAVIGATE_MAPS -> navigateMaps(command.params["destination"].orEmpty())
            AppCommand.GET_CURRENT_LOCATION -> getCurrentLocationForUser()
            AppCommand.WEB_SEARCH -> webResearch.search(command.params["query"].orEmpty()).toToolExecutionResult()
            AppCommand.GET_WEATHER -> webResearch.weather(command.params["location"].orEmpty()).toToolExecutionResult()
            AppCommand.GET_LATEST_NEWS -> webResearch.news(command.params["topic"].orEmpty()).toToolExecutionResult()
            AppCommand.SAVE_PARKING -> saveParkingSpot(command.params["note"].orEmpty())
            AppCommand.FIND_PARKING -> findParkingSpot()
            AppCommand.CAMERA_VISION -> ToolExecutionResult(
                success = true,
                message = "Opening MYRA Camera Vision. Capture a photo to analyze it with Gemini Live.",
                navigateRoute = "camera",
            )
            AppCommand.SCAN_BARCODE -> ToolExecutionResult(
                success = true,
                message = "Opening Camera & Barcode Scanner.",
                navigateRoute = "scanner",
            )
            AppCommand.SET_REMINDER -> {
                val title = command.params["title"].orEmpty()
                val minutes = command.params["delay_minutes"]?.toIntOrNull() ?: 0
                val scheduled = ReminderScheduler.schedule(context, title, minutes)
                if (!scheduled) ToolExecutionResult(false, "रिमाइंडर सेट नहीं हो सका। समय 1 मिनट से 7 दिन के बीच होना चाहिए।")
                else {
                    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    ToolExecutionResult(
                        true,
                        if (permissionGranted) "रिमाइंडर सेट हो गया: $title। लगभग $minutes मिनट बाद सूचना मिलेगी। Android बैटरी प्रबंधन के कारण कुछ देरी हो सकती है।"
                        else "रिमाइंडर समय पर सेट हो गया: $title। सूचना देखने के लिए MYRA की Notifications permission चालू करें।",
                    )
                }
            }
            AppCommand.DEVICE_STATUS -> {
                val status = telemetry.readSystemStatus(torchEnabled)
                ToolExecutionResult(
                    success = true,
                    message = "Battery ${status.batteryPercent}%${if (status.isCharging) " (Charging)" else ""}, RAM ${status.usedRamMb}/${status.totalRamMb} MB, Time ${status.timeLabel}.",
                )
            }
            AppCommand.SEND_TO_PC -> {
                val payload = command.params["payload"].orEmpty()
                if (payload.isBlank()) {
                    ToolExecutionResult(false, "Nothing specified to send to PC Companion.")
                } else {
                    ToolExecutionResult(
                        success = true,
                        message = "Queued payload for connected PC Companion.",
                        forwardToPcPayload = payload,
                    )
                }
            }
            else -> ToolExecutionResult(false, "Unsupported command: ${command.type}")
        }
    }


    private fun WebResearchService.Result.toToolExecutionResult(): ToolExecutionResult =
        ToolExecutionResult(success = success, message = message)

    private suspend fun searchYouTube(rawQuery: String): ToolExecutionResult {
        val query = rawQuery.trim()
        if (query.isBlank()) return ToolExecutionResult(false, "YouTube पर कौन-सा गाना चलाना है?")
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        return withContext(Dispatchers.Main) {
            runCatching {
                val youtubeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val openedInApp = runCatching { context.startActivity(youtubeIntent) }.isSuccess
                if (!openedInApp) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return@runCatching ToolExecutionResult(
                        false,
                        "YouTube app नहीं खुला। Search browser में खुला है; सही गाना चुनना होगा।",
                    )
                }

                if (!AccessibilityHelperService.isEnabled(context) || AccessibilityHelperService.instance == null) {
                    return@runCatching ToolExecutionResult(
                        true,
                        "YouTube search खुल गया। MYRA को Accessibility अनुमति दें तो matching result खुद चुन सकेगी।",
                    )
                }

                var selected = false
                // Search results may take several seconds to populate on mobile.
                for (attempt in 0 until 18) {
                    delay(700L)
                    if (AccessibilityHelperService.instance?.tapMatchingYouTubeResult(query) == true) {
                        selected = true
                        break
                    }
                }
                if (!selected) {
                    ToolExecutionResult(
                        false,
                        "YouTube search खुला है, लेकिन सही गाना नहीं पहचान पाई। MYRA Accessibility Service चालू रखें या गाने का नाम और कलाकार बोलकर दोबारा कोशिश करें।",
                    )
                } else {
                    // Selecting a result is not proof of playback. Wait for the
                    // watch page and use only its explicit Play control if paused.
                    var playbackStarted = false
                    for (attempt in 0 until 14) {
                        delay(650L)
                        if (AccessibilityHelperService.instance?.startYouTubePlaybackIfPaused() == true) {
                            playbackStarted = true
                            break
                        }
                    }
                    if (playbackStarted) {
                        ToolExecutionResult(true, "YouTube में matching गाना खोलकर Playback शुरू/चालू होने की पुष्टि मिल गई।")
                    } else {
                        ToolExecutionResult(
                            false,
                            "सही YouTube result खुल गया, लेकिन Playback की पुष्टि नहीं मिली। YouTube में Play दबाएँ; MYRA Accessibility Service और YouTube player controls जाँचें।",
                        )
                    }
                }
            }.getOrElse { ToolExecutionResult(false, "YouTube नहीं खुल पाया: ${it.message}") }
        }
    }

    private suspend fun openApp(rawName: String): ToolExecutionResult {
        val query = rawName.trim().lowercase()
        if (query.isEmpty()) return ToolExecutionResult(false, "Which app should I open?")

        if (query == "settings" || query == "android settings") {
            return withContext(Dispatchers.Main) {
                runCatching {
                    val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ToolExecutionResult(true, "Opened Android Settings.")
                }.getOrElse { ToolExecutionResult(false, "Failed to open Settings: ${it.message}") }
            }
        }

        val pm = context.packageManager
        val knownPkg = KNOWN_APP_PACKAGES.entries.firstOrNull { (k, _) ->
            query == k || query.contains(k)
        }?.value

        if (knownPkg != null) {
            val launchIntent = pm.getLaunchIntentForPackage(knownPkg)
            if (launchIntent != null) {
                return withContext(Dispatchers.Main) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    ToolExecutionResult(true, "Opened $rawName.")
                }
            }
        }

        // Fallback: scan launchable apps by label
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        val matched = resolveInfos.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            label == query || label.contains(query) || query.contains(label)
        }

        return if (matched != null) {
            val launch = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launch != null) {
                withContext(Dispatchers.Main) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                }
                ToolExecutionResult(true, "Opened ${matched.loadLabel(pm)}.")
            } else {
                ToolExecutionResult(false, "Could not create launch intent for $rawName.")
            }
        } else {
            ToolExecutionResult(false, "App '$rawName' is not installed on this device.")
        }
    }

    private suspend fun closeCurrentApp(): ToolExecutionResult {
        val service = AccessibilityHelperService.instance
        if (service == null || !AccessibilityHelperService.isEnabled(context)) {
            withContext(Dispatchers.Main) {
                telemetry.openAccessibilitySettings()
            }
            return ToolExecutionResult(
                false,
                "MYRA Accessibility Service is OFF. Opened Accessibility settings so you can enable it.",
            )
        }
        val ok = withContext(Dispatchers.Main) { service.closeCurrentApp() }
        return if (ok) {
            ToolExecutionResult(true, "Returned to Home screen.")
        } else {
            ToolExecutionResult(false, "Could not perform Home action via Accessibility.")
        }
    }

    private suspend fun goBack(): ToolExecutionResult {
        val service = AccessibilityHelperService.instance
        if (service == null || !AccessibilityHelperService.isEnabled(context)) {
            withContext(Dispatchers.Main) { telemetry.openAccessibilitySettings() }
            return ToolExecutionResult(false, "Enable MYRA Accessibility Service to navigate back.")
        }
        val ok = withContext(Dispatchers.Main) { service.goBack() }
        return ToolExecutionResult(ok, if (ok) "Navigated back." else "Back action failed.")
    }

    private suspend fun clickText(text: String): ToolExecutionResult {
        val service = AccessibilityHelperService.instance
            ?: return ToolExecutionResult(false, "Enable MYRA Accessibility Service to tap screen text.")
        val ok = withContext(Dispatchers.Main) { service.clickOnText(text) }
        return ToolExecutionResult(ok, if (ok) "Tapped '$text'." else "Could not find '$text' on screen.")
    }

    private suspend fun typeText(text: String): ToolExecutionResult {
        val service = AccessibilityHelperService.instance
            ?: return ToolExecutionResult(false, "Enable MYRA Accessibility Service to type text.")
        val ok = withContext(Dispatchers.Main) { service.typeText(text) }
        return ToolExecutionResult(ok, if (ok) "Typed text." else "No editable input field focused.")
    }

    private suspend fun scroll(forward: Boolean): ToolExecutionResult {
        val service = AccessibilityHelperService.instance
            ?: return ToolExecutionResult(false, "Enable MYRA Accessibility Service to scroll.")
        val ok = withContext(Dispatchers.Main) {
            if (forward) service.scrollDown() else service.scrollUp()
        }
        return ToolExecutionResult(ok, if (ok) "Scrolled." else "Could not scroll current view.")
    }

    private suspend fun callPrimeContact(index: Int): ToolExecutionResult {
        val primes = settingsRepo.getPrimeContacts()
        if (primes.isEmpty()) {
            return ToolExecutionResult(false, "No Prime Contacts saved yet. Add one in MYRA Settings.")
        }
        val contact = primes.getOrNull(index)
            ?: return ToolExecutionResult(false, "Prime Contact #${index + 1} does not exist.")
        return dialOrCallNumber(contact.name, contact.number)
    }

    private suspend fun messagePrimeContact(index: Int, message: String): ToolExecutionResult {
        val primes = settingsRepo.getPrimeContacts()
        if (primes.isEmpty()) {
            return ToolExecutionResult(false, "No Prime Contacts saved yet. Add one in MYRA Settings.")
        }
        val contact = primes.getOrNull(index)
            ?: return ToolExecutionResult(false, "Prime Contact #${index + 1} does not exist.")
        return launchSmsDraft(contact.name, contact.number, message)
    }

    private suspend fun makeCall(target: String): ToolExecutionResult {
        val clean = target.trim()
        if (clean.isEmpty()) return ToolExecutionResult(false, "Please specify who to call.")
        val resolved = resolvePhoneNumber(clean)
        return if (resolved != null) {
            dialOrCallNumber(resolved.first, resolved.second)
        } else {
            ToolExecutionResult(false, "Could not find contact '$clean' or Contacts permission is missing.")
        }
    }

    private suspend fun dialOrCallNumber(displayName: String, number: String): ToolExecutionResult {
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED

        return withContext(Dispatchers.Main) {
            runCatching {
                val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
                val intent = Intent(action, Uri.parse("tel:${Uri.encode(number)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                if (hasCallPerm) {
                    ToolExecutionResult(true, "Calling $displayName ($number).")
                } else {
                    ToolExecutionResult(
                        true,
                        "Opened phone dialer for $displayName (grant Phone Call permission for direct calling).",
                    )
                }
            }.getOrElse {
                ToolExecutionResult(false, "Unable to initiate call: ${it.message}")
            }
        }
    }

    private suspend fun draftSms(target: String, message: String): ToolExecutionResult {
        val clean = target.trim()
        if (clean.isEmpty()) return ToolExecutionResult(false, "Please specify SMS recipient.")
        val resolved = resolvePhoneNumber(clean)
            ?: return ToolExecutionResult(false, "Contact '$clean' not found or Contacts permission missing.")
        return launchSmsDraft(resolved.first, resolved.second, message)
    }

    private suspend fun launchSmsDraft(displayName: String, number: String, message: String): ToolExecutionResult {
        return withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolExecutionResult(true, "Opened SMS draft for $displayName.")
            }.getOrElse {
                ToolExecutionResult(false, "No SMS app available: ${it.message}")
            }
        }
    }

    private suspend fun openWhatsApp(target: String, message: String, voiceCallHandoff: Boolean = false): ToolExecutionResult {
        val clean = target.trim()
        if (clean.isEmpty()) return ToolExecutionResult(false, "Please specify WhatsApp contact.")
        val resolved = resolvePhoneNumber(clean)
            ?: return ToolExecutionResult(false, "Contact '$clean' not found or Contacts permission missing.")
        val digits = resolved.second.filter { it.isDigit() || it == '+' }.removePrefix("+")
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        val uri = if (message.isNotBlank()) {
            Uri.parse("https://wa.me/$digits?text=$encodedMsg")
        } else {
            Uri.parse("https://wa.me/$digits")
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                if (voiceCallHandoff) {
                    return@runCatching ToolExecutionResult(
                        true,
                        "Opened WhatsApp chat for ${resolved.first}. Tap the voice-call button manually; WhatsApp does not expose a supported direct-call API.",
                    )
                }
                if (message.isBlank()) {
                    return@runCatching ToolExecutionResult(true, "Opened WhatsApp chat for ${resolved.first}.")
                }

                if (!AccessibilityHelperService.isEnabled(context) || AccessibilityHelperService.instance == null) {
                    return@runCatching ToolExecutionResult(
                        false,
                        "WhatsApp chat खुल गई और पूरा message तैयार है, लेकिन MYRA Accessibility अनुमति बंद है। Send अपने-आप नहीं दबाया गया।",
                    )
                }

                var sentButtonTapped = false
                for (attempt in 0 until 8) {
                    delay(500L)
                    val service = AccessibilityHelperService.instance
                    if (service?.tapWhatsAppSendForExactMessage(message) == true) {
                        sentButtonTapped = true
                        break
                    }
                }
                if (sentButtonTapped) {
                    ToolExecutionResult(
                        true,
                        "आपकी 'हाँ, भेज दो' पुष्टि के बाद WhatsApp का Send बटन दबा दिया। Message delivery status WhatsApp में देखें।",
                    )
                } else {
                    ToolExecutionResult(
                        false,
                        "WhatsApp chat खुल गई और पूरा message तैयार है, लेकिन exact message composer/Send button verify नहीं हुआ। सुरक्षा के लिए MYRA ने Send नहीं दबाया।",
                    )
                }
            }.getOrElse {
                ToolExecutionResult(false, "Could not open or send WhatsApp message: ${it.message}")
            }
        }
    }

    private fun resolvePhoneNumber(target: String): Pair<String, String>? {
        val digitsOnly = target.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 6 && digitsOnly.count { it.isDigit() } >= 6 && target.none { it.isLetter() }) {
            return target to digitsOnly
        }

        // Check Prime Contacts first
        settingsRepo.getPrimeContacts().firstOrNull {
            it.name.equals(target, ignoreCase = true) || it.name.contains(target, ignoreCase = true)
        }?.let { return it.name to it.number }

        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$target%")

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: target
                val num = cursor.getString(1) ?: return null
                return name to num
            }
        }
        return null
    }

    private fun adjustVolume(direction: Int): ToolExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionResult(false, "AudioManager unavailable.")
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val dirLabel = if (direction == AudioManager.ADJUST_RAISE) "increased" else "decreased"
        return ToolExecutionResult(true, "Media volume $dirLabel ($current/$max).")
    }

    private fun setFlashlight(enable: Boolean): ToolExecutionResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolExecutionResult(false, "CameraManager unavailable.")
        return runCatching {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolExecutionResult(false, "No camera flash hardware found on this device.")

            cameraManager.setTorchMode(cameraId, enable)
            torchEnabled = enable
            ToolExecutionResult(
                success = true,
                message = if (enable) "Flashlight turned ON." else "Flashlight turned OFF.",
                torchStateChanged = enable,
            )
        }.getOrElse {
            ToolExecutionResult(false, "Could not toggle flashlight: ${it.message}")
        }
    }

    private suspend fun openWifiPanel(): ToolExecutionResult {
        return withContext(Dispatchers.Main) {
            runCatching {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolExecutionResult(
                    true,
                    "Opened Android Wi-Fi & Internet Panel (Android restricts apps from toggling Wi-Fi silently).",
                )
            }.getOrElse {
                ToolExecutionResult(false, "Could not open Wi-Fi settings: ${it.message}")
            }
        }
    }

    private suspend fun openBluetoothSettings(): ToolExecutionResult {
        return withContext(Dispatchers.Main) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ToolExecutionResult(
                    true,
                    "Opened Android Bluetooth settings so you can toggle or pair devices safely.",
                )
            }.getOrElse {
                ToolExecutionResult(false, "Could not open Bluetooth settings: ${it.message}")
            }
        }
    }

    private suspend fun navigateMaps(destination: String): ToolExecutionResult {
        if (destination.isBlank()) return ToolExecutionResult(false, "Where should I navigate to?")
        val encoded = Uri.encode(destination)
        return withContext(Dispatchers.Main) {
            val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (navIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(navIntent)
                ToolExecutionResult(true, "Started Google Maps navigation to $destination.")
            } else {
                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    context.startActivity(geoIntent)
                    ToolExecutionResult(true, "Opened Maps for $destination.")
                }.getOrElse {
                    ToolExecutionResult(false, "No Maps app available to navigate to $destination.")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationForUser(): ToolExecutionResult {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return ToolExecutionResult(false, "MYRA को आपकी लोकेशन की अनुमति नहीं मिली है। Android Settings में MYRA → Permissions → Location को Allow while using the app करें।")
        }
        val location = runCatching { getBestCurrentLocation() }.getOrNull()
            ?: return ToolExecutionResult(false, "अभी GPS लोकेशन नहीं मिल रही है। फोन की Location चालू करें और फिर कोशिश करें।")
        val address = runCatching {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.let { place ->
                        listOfNotNull(place.featureName, place.subLocality, place.locality, place.subAdminArea, place.adminArea)
                            .filter { it.isNotBlank() }.distinct().joinToString(", ")
                    }
            } else null
        }.getOrNull().orEmpty()
        val coordinates = "अक्षांश ${"%.5f".format(Locale.US, location.latitude)}, देशांतर ${"%.5f".format(Locale.US, location.longitude)}"
        val accuracy = if (location.hasAccuracy()) " (लगभग ${location.accuracy.toInt()} मीटर सटीकता)" else ""
        return ToolExecutionResult(true, "आपकी वर्तमान लोकेशन: ${address.ifBlank { coordinates }}। $coordinates$accuracy")
    }

    private suspend fun saveParkingSpot(note: String): ToolExecutionResult {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return ToolExecutionResult(false, "Location permission is required to save your parking spot.")
        }

        val loc = getBestCurrentLocation()
            ?: return ToolExecutionResult(
                false,
                "Could not acquire GPS fix right now. Ensure device Location is turned ON.",
            )

        val cleanNote = note.trim().ifEmpty { "Saved via MYRA" }
        myraRepo.saveParkingSpot(loc.latitude, loc.longitude, cleanNote)
        return ToolExecutionResult(
            true,
            "Parking spot saved at (${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}).",
        )
    }

    private suspend fun findParkingSpot(): ToolExecutionResult {
        val spot = myraRepo.getLatestParkingSpot()
            ?: return ToolExecutionResult(false, "No parking spot saved yet.")
        val uri = Uri.parse("google.navigation:q=${spot.latitude},${spot.longitude}&mode=w")
        return withContext(Dispatchers.Main) {
            val navIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (navIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(navIntent)
                ToolExecutionResult(true, "Navigating back to your parked vehicle (${spot.note}).")
            } else {
                val geoIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:${spot.latitude},${spot.longitude}?q=${spot.latitude},${spot.longitude}(Parked+Car)"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    context.startActivity(geoIntent)
                    ToolExecutionResult(true, "Opened saved parking pin (${spot.note}).")
                }.getOrElse {
                    ToolExecutionResult(false, "No Maps app found. Saved coordinates: ${spot.latitude}, ${spot.longitude}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getBestCurrentLocation(): Location? {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val current = suspendCancellableCoroutine<Location?> { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        if (current != null) return current

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun acceptRingingCall(): ToolExecutionResult {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            return ToolExecutionResult(false, "Answer Phone Calls permission is not granted.")
        }
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return ToolExecutionResult(false, "TelecomManager unavailable.")
        return runCatching {
            @Suppress("DEPRECATION")
            tm.acceptRingingCall()
            ToolExecutionResult(true, "Call accepted.")
        }.getOrElse {
            ToolExecutionResult(false, "Could not accept call: ${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun rejectCall(): ToolExecutionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ToolExecutionResult(false, "Programmatic call rejection requires Android 9+ (API 28).")
        }
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            return ToolExecutionResult(false, "Answer Phone Calls permission is not granted.")
        }
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return ToolExecutionResult(false, "TelecomManager unavailable.")
        return runCatching {
            @Suppress("DEPRECATION")
            val ended = tm.endCall()
            ToolExecutionResult(ended, if (ended) "Call rejected." else "Could not end call.")
        }.getOrElse {
            ToolExecutionResult(false, "Could not reject call: ${it.message}")
        }
    }

    companion object {
        private val KNOWN_APP_PACKAGES = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "phone" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "play store" to "com.android.vending",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "zoom" to "us.zoom.videomeetings",
            "meet" to "com.google.android.apps.meetings",
            "teams" to "com.microsoft.teams",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "linkedin" to "com.linkedin.android",
        )
    }
}
