package dev.pocketharness

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeNavigationAndMissingCredentialState() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A little space.\nFor big ideas.").assertIsDisplayed()
        compose.onNodeWithText("Ask anything, or describe a change…").performTextInput("Explain Kotlin")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithText("Add your AI API key in Settings first.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.onNodeWithText("Code").performClick()
        compose.onNodeWithText("Connect to your code").assertIsDisplayed()
        compose.onNodeWithText("Open settings").performClick()
        compose.onNodeWithText("Make it yours.").assertIsDisplayed()
        compose.onNodeWithText("API base URL").assertExists()
        compose.onNodeWithText("Model ID").assertExists()
        compose.onNodeWithText("AI API key").assertExists()
    }
}
