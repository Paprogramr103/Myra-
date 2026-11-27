package com.myra.assistant

import android.content.Context
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.data.preferences.SecureSettingsRepository
import com.myra.assistant.model.GeminiModelOption
import com.myra.assistant.model.MyraSettings
import com.myra.assistant.model.PersonalityMode
import com.myra.assistant.model.VoiceOption
import com.myra.assistant.ui.screens.MainDashboardScreen
import com.myra.assistant.ui.theme.MyraTheme
import com.myra.assistant.viewmodel.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyraRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name and defaults match MYRA specification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("MYRA", context.getString(R.string.app_name))

        val repo = SecureSettingsRepository(context)
        val defaults = repo.settings.value
        assertEquals(VoiceOption.AOEDE, defaults.voice)
        assertEquals(GeminiModelOption.NATIVE_AUDIO, defaults.model)
        assertEquals(PersonalityMode.GF, defaults.personality)
    }

    @Test
    fun `prime contacts save and remove cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SecureSettingsRepository(context)
        repo.addPrimeContact("Priya", "+919876543210")
        repo.addPrimeContact("Mom", "+919123456789")
        val list = repo.getPrimeContacts()
        assertTrue(list.any { it.name == "Priya" && it.number == "+919876543210" })
        assertTrue(list.any { it.name == "Mom" })
        repo.removePrimeContact(0)
        assertEquals(1, repo.getPrimeContacts().size)
    }

    @Test
    fun `system prompt builds with personality and user name`() {
        val prompt = GeminiLiveClient.buildSystemPrompt(
            MyraSettings(userName = "Aarav", personality = PersonalityMode.GF),
            conversationContext = "User: My favourite language is Hindi\\nMYRA: I will remember that.",
        )
        assertTrue(prompt.contains("Aarav"))
        assertTrue(prompt.contains("Hinglish"))
        assertTrue(prompt.contains("speaking ALOUD"))
        assertTrue(prompt.contains("Recent conversation context"))
        assertTrue(prompt.contains("My favourite language is Hindi"))
        assertTrue(prompt.contains("NATURAL CONVERSATION AND EMOTIONAL UNDERSTANDING"))
        assertTrue(prompt.contains("Understand humor, teasing, irony and light sarcasm"))
        assertTrue(prompt.contains("save_emotional_memory"))
        assertTrue(prompt.contains("as a dated event"))
        assertTrue(prompt.contains("Use phone/action tools only when the user clearly requests an action"))
    }

    @Test
    fun `main dashboard renders orb waveform mic and text input`() {
        // Freeze the dashboard's intentional infinite orb animation for deterministic UI assertions.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MyraTheme {
                MainDashboardScreen(
                    uiState = MainUiState(),
                    settings = MyraSettings(),
                    chatMessages = emptyList(),
                    otpAlert = null,
                    onToggleMic = {},
                    onInterrupt = {},
                    onSendText = {},
                    onQuickCommand = {},
                    onConnectClick = {},
                    onDisconnectClick = {},
                    onAcceptCall = {},
                    onRejectCall = {},
                    onDismissOtp = {},
                    onOpenSettings = {},
                    onOpenDiagnostics = {},
                    onOpenConnect = {},
                    onOpenScanner = {},
                    onOpenCamera = {},
                    onOpenMacros = {},
                )
            }
        }
        composeRule.onNodeWithTag("myra_orb").assertIsDisplayed()
        composeRule.onNodeWithTag("myra_waveform").assertIsDisplayed()
        composeRule.onNodeWithTag("mic_button").assertExists()
        composeRule.onNodeWithTag("text_command_input").assertIsDisplayed()
    }
}
