package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.ui.chat.VaultCodePromptUi
import com.m57.hermescontrol.ui.chat.VaultSaveLoginPromptUi
import com.m57.hermescontrol.ui.chat.VaultUnlockPromptUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class VaultPromptCardsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testVaultUnlockCard_interaction() {
        var confirmedPassword = ""
        var dismissed = false

        composeTestRule.setContent {
            VaultUnlockCard(
                prompt =
                    VaultUnlockPromptUi(
                        requestId = "req-1",
                        sessionId = "sess-1",
                        backend = "onepassword",
                        displayName = "1Password",
                    ),
                onConfirm = { confirmedPassword = it },
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithTag("vault_unlock_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unlock 1Password").assertIsDisplayed()

        composeTestRule.onNodeWithTag("vault_unlock_password_input").performTextInput("mypass123")
        composeTestRule.onNodeWithTag("vault_unlock_confirm").performClick()

        assertEquals("mypass123", confirmedPassword)

        composeTestRule.onNodeWithTag("vault_unlock_dismiss").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun testVaultSaveLoginCard_interaction() {
        var savedIdentifier = ""
        var savedPassword = ""
        var dismissed = false

        composeTestRule.setContent {
            VaultSaveLoginCard(
                prompt =
                    VaultSaveLoginPromptUi(
                        requestId = "req-save",
                        sessionId = "sess-1",
                        origin = "https://github.com/login",
                        site = "GitHub",
                    ),
                onConfirm = { id, pass ->
                    savedIdentifier = id
                    savedPassword = pass
                },
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithTag("vault_save_login_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_save_login_identifier_input").performTextInput("testuser@example.com")
        composeTestRule.onNodeWithTag("vault_save_login_password_input").performTextInput("supersecret")

        composeTestRule.onNodeWithTag("vault_save_login_confirm").performClick()
        assertEquals("testuser@example.com", savedIdentifier)
        assertEquals("supersecret", savedPassword)

        composeTestRule.onNodeWithTag("vault_save_login_dismiss").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun testVaultCodeCard_interaction() {
        var submittedCode = ""
        var dismissed = false

        composeTestRule.setContent {
            VaultCodeCard(
                prompt =
                    VaultCodePromptUi(
                        requestId = "req-code",
                        sessionId = "sess-1",
                        site = "GitHub",
                        hint = "SMS verification to ***1234",
                    ),
                onConfirm = { submittedCode = it },
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithTag("vault_code_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("SMS verification to ***1234").assertIsDisplayed()

        composeTestRule.onNodeWithTag("vault_code_input").performTextInput("654321")
        composeTestRule.onNodeWithTag("vault_code_confirm").performClick()
        assertEquals("654321", submittedCode)

        composeTestRule.onNodeWithTag("vault_code_dismiss").performClick()
        assertEquals(true, dismissed)
    }
}
