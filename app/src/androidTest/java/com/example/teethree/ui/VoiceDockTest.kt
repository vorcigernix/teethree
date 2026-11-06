package com.example.teethree.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ComposerDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceDockTest {
    @get:Rule val compose = createComposeRule()

    private val voiceMode = mutableStateOf(true)

    private fun show(dark: Boolean = false, blur: Boolean = true) {
        compose.setContent {
            T3Theme(if (dark) AppTheme.Dark else AppTheme.Light) {
                CompositionLocalProvider(
                    LocalComposerInputMode provides
                        ComposerInputMode(voice = voiceMode.value, onChange = { voiceMode.value = it }),
                    LocalGlassBlur provides blur,
                ) {
                    TaskComposer(
                        draft = ComposerDraft(),
                        onDraftChange = {},
                        onSubmit = {},
                        onAttach = {},
                        onRemoveAttachment = {},
                        modifier = Modifier.width(412.dp),
                        modelLabel = "Codex · high",
                        modeLabel = "Ask first",
                        onModelOptions = {},
                        onModeOptions = {},
                        projectLabel = "showcase",
                    )
                }
            }
        }
    }

    @Test
    fun voiceLeadsAndTheKeyboardIsOneTapAway() {
        show()
        compose.onNodeWithTag("voice-dock").assertIsDisplayed()
        compose.onNodeWithText("Tap to talk  ·  hold for push-to-talk").assertIsDisplayed()
        compose.onNodeWithContentDescription("Type instead").performClick()
        compose.runOnIdle { assertFalse(voiceMode.value) }
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Talk instead").performClick()
        compose.runOnIdle { assertTrue(voiceMode.value) }
        compose.onNodeWithTag("voice-dock").assertIsDisplayed()
    }

    @Test
    fun lowEndDevicesGetTheSolidPanel() {
        show(blur = false)
        compose.onNodeWithTag("voice-dock").assertIsDisplayed()
        compose.onNodeWithContentDescription("Type instead").performClick()
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
    }

    @Test
    fun darkThemeDock() {
        show(dark = true)
        compose.onNodeWithTag("voice-dock").assertIsDisplayed()
    }
}
