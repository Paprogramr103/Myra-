package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AccessibilityHelperService : AccessibilityService() {

    private data class MacroStep(val action: String, val value: String = "")

    private val macroHandler = Handler(Looper.getMainLooper())
    private val recordedSteps = mutableListOf<MacroStep>()
    private var recordingMacro = false
    private var replayingMacro = false
    private var macroName: String = ""
    private var macroPackage: String = ""
    private var pendingMacroName: String? = null
    private val savedMacros = linkedMapOf<String, Pair<String, List<MacroStep>>>()
    private val maxMacroSteps = 30

    /**
     * Starts an explicit, in-memory macro recording session for the current app.
     * Text entry is intentionally never recorded to avoid capturing passwords,
     * messages, OTPs, or other private input.
     */
    fun beginMacroRecording(name: String): Boolean {
        val packageName = rootInActiveWindow?.packageName?.toString().orEmpty()
        val safeName = name.trim().take(40)
        if (packageName.isBlank() || safeName.isBlank() || replayingMacro) return false
        recordedSteps.clear()
        macroName = safeName
        macroPackage = packageName
        recordingMacro = true
        return true
    }

    /** Stops recording and keeps the macro in memory for this service session. */
    fun stopMacroRecording(): Boolean {
        if (!recordingMacro) return false
        recordingMacro = false
        if (recordedSteps.isEmpty()) return false
        savedMacros[macroName] = macroPackage to recordedSteps.toList()
        _macroStatus.value = "Saved macro: $macroName (${recordedSteps.size} steps)"
        while (savedMacros.size > 10) {
            savedMacros.remove(savedMacros.keys.first())
        }
        return true
    }

    fun availableMacroNames(): List<String> = savedMacros.keys.toList()

    fun isMacroRecording(): Boolean = recordingMacro

    fun removeMacro(name: String): Boolean {
        val removed = savedMacros.remove(name) != null
        if (removed) _macroStatus.value = "Deleted macro: $name"
        return removed
    }

    fun launchAndReplayMacro(name: String): Boolean {
        val macro = savedMacros[name] ?: return false
        if (recordingMacro || replayingMacro || pendingMacroName != null) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(macro.first) ?: return false
        pendingMacroName = name
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(launchIntent); _macroStatus.value = "Waiting for target app before playback."; true }.getOrElse { pendingMacroName = null; false }
    }

    /**
     * Replays only a macro recorded for the same foreground app. Each step is
     * revalidated against the live accessibility tree; playback aborts on any
     * mismatch or failed action. The caller must obtain user confirmation first.
     */
    fun replayMacro(name: String, onFinished: (Boolean) -> Unit): Boolean {
        val macro = savedMacros[name] ?: return false
        if (recordingMacro || replayingMacro) return false
        val (expectedPackage, steps) = macro
        if (steps.isEmpty() || steps.size > maxMacroSteps) return false
        replayingMacro = true
        var index = 0
        fun runNext() {
            val currentPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (currentPackage != expectedPackage) {
                replayingMacro = false
                _macroStatus.value = "Playback stopped: target app changed."
                onFinished(false)
                return
            }
            if (index >= steps.size) {
                replayingMacro = false
                _macroStatus.value = "Macro completed successfully."
                onFinished(true)
                return
            }
            val step = steps[index++]
            val success = when (step.action) {
                "click_text" -> clickOnText(step.value)
                "scroll_down" -> scrollDown()
                "scroll_up" -> scrollUp()
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                else -> false
            }
            if (!success) {
                replayingMacro = false
                _macroStatus.value = "Playback stopped: an action was not verified."
                onFinished(false)
            } else {
                macroHandler.postDelayed({ runNext() }, 450L)
            }
        }
        runNext()
        return true
    }

    private fun recordMacroStep(action: String, value: String = "") {
        if (!recordingMacro || replayingMacro || recordedSteps.size >= maxMacroSteps) return
        if (action == "click_text") {
            val blocked = listOf("send", "call", "delete", "remove", "pay", "purchase", "buy", "transfer", "confirm", "submit", "otp", "password", "logout", "भेज", "कॉल", "हटाएं", "भुगतान", "खरीद", "ओटीपी", "पासवर्ड")
            if (blocked.any { value.contains(it, ignoreCase = true) }) return
        }
        val currentPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (currentPackage.isBlank()) return
        // Recording may be started from MYRA before opening the target app.
        // Bind the macro to the first app in which a recordable action occurs.
        if (macroPackage == packageName && currentPackage != packageName) {
            macroPackage = currentPackage
        }
        if (currentPackage != macroPackage) return
        recordedSteps += MacroStep(action, value.take(120))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (recordingMacro && !replayingMacro) {
            val eventPackage = event.packageName?.toString().orEmpty()
            if (eventPackage.isNotBlank() && eventPackage != packageName) {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                        val label = event.text?.joinToString(" ")?.trim().orEmpty()
                            .ifBlank { event.contentDescription?.toString().orEmpty().trim() }
                        if (label.isNotBlank()) recordMacroStep("click_text", label)
                    }
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                        if (Build.VERSION.SDK_INT >= 28 && event.scrollDeltaY != 0) {
                            recordMacroStep(if (event.scrollDeltaY > 0) "scroll_down" else "scroll_up")
                        }
                    }
                }
            }
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val queuedName = pendingMacroName ?: return
        val foregroundPackage = event.packageName?.toString().orEmpty()
        if (foregroundPackage.isBlank() || foregroundPackage != savedMacros[queuedName]?.first) return
        pendingMacroName = null
        macroHandler.postDelayed({ replayMacro(queuedName) { success -> if (!success) _macroStatus.value = "Macro playback stopped for safety." } }, 900L)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun closeCurrentApp(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        if (success) recordMacroStep("back")
        return success
    }

    fun clickOnText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(query)
        for (node in matches) {
            if (clickNodeOrParent(node)) {
                recordMacroStep("click_text", query)
                return true
            }
        }
        return false
    }

    /**
     * Selects a visible YouTube search result only when its title substantially
     * matches the spoken query. It never blindly taps the first unrelated row.
     */
    fun tapMatchingYouTubeResult(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.google.android.youtube") return false
        val tokens = query.lowercase()
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length > 1 && it !in setOf("youtube", "play", "song", "songs", "video", "पर", "चलाओ") }
            .distinct()
        if (tokens.isEmpty()) return false

        data class Candidate(val node: AccessibilityNodeInfo, val score: Int, val length: Int)
        val candidates = mutableListOf<Candidate>()
        fun visit(node: AccessibilityNodeInfo) {
            val label = listOf(node.text?.toString(), node.contentDescription?.toString())
                .filterNotNull().joinToString(" ").lowercase()
            if (label.isNotBlank() && node.isVisibleToUser) {
                val score = tokens.count { label.contains(it) }
                if (score > 0) candidates += Candidate(node, score, label.length)
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        val minimumScore = if (tokens.size == 1) 1 else 2
        val best = candidates
            .filter { it.score >= minimumScore }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.length })
            .firstOrNull() ?: return false
        return clickNodeOrParent(best.node)
    }

    /**
     * Sends only when the WhatsApp composer contains the exact message that
     * the user just confirmed, and the foreground package is WhatsApp.
     */
    fun tapWhatsAppSendForExactMessage(expectedMessage: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return false
        val expected = expectedMessage.trim()
        if (expected.isEmpty()) return false

        fun findExactComposer(node: AccessibilityNodeInfo): Boolean {
            if (node.isEditable && node.text?.toString()?.trim() == expected) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (findExactComposer(child)) return true
            }
            return false
        }
        if (!findExactComposer(root)) return false

        fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val label = listOf(node.text?.toString(), node.contentDescription?.toString())
                .filterNotNull().joinToString(" ").trim().lowercase()
            val isSend = label == "send" || label.contains("send message") ||
                label == "भेजें" || label == "भेजो" || label.contains("संदेश भेजें")
            if (isSend && node.isClickable && !node.isEditable && node.isEnabled) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                findSendButton(child)?.let { return it }
            }
            return null
        }
        val button = findSendButton(root) ?: return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findEditableNode(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: return false
        val success = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (success) recordMacroStep("scroll_down")
        return success
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: return false
        val success = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (success) recordMacroStep("scroll_up")
        return success
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        if (node.isEditable) return node
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private val _macroStatus = MutableStateFlow("Macro automation is idle.")
        val macroStatus: StateFlow<String> = _macroStatus

        @Volatile
        var instance: AccessibilityHelperService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = ComponentName(context, AccessibilityHelperService::class.java).flattenToString()
            val shortExpected = ComponentName(context, AccessibilityHelperService::class.java).flattenToShortString()
            return enabledServices.split(':').any {
                it.equals(expected, ignoreCase = true) || it.equals(shortExpected, ignoreCase = true)
            }
        }
    }
}
