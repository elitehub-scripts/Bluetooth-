package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BluetoothGlobalShareUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `navigation across all primary tabs and contacts settings works cleanly`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Bluetooth Global Share").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_nearby").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Nearby Bluetooth").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_messages").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Encrypted Messages").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_files").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Offline File Sharing").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_network").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Relay Network Graph").assertIsDisplayed()

        composeTestRule.onNodeWithTag("topbar_contacts_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Contacts & QR Keys").assertIsDisplayed()

        composeTestRule.onNodeWithTag("topbar_settings_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Settings & Security").assertIsDisplayed()
    }
}
