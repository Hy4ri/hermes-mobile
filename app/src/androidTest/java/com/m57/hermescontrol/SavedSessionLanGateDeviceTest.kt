package com.m57.hermescontrol

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Runs only on a disposable API 37+ emulator with LAN permission revoked externally. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class SavedSessionLanGateDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun cookieOnlySavedSessionPromptsThenDenialOffersAuthLoginWithoutConnecting() {
        assumeTrue(
            "Explicit disposable-emulator opt-in is required",
            InstrumentationRegistry.getArguments().getString("disposableLanFixture") == "true",
        )
        // Never change stored credentials on a real device or a non-emulator installation.
        assertTrue(
            "This fixture modifies saved auth and requires a disposable emulator",
            Build.HARDWARE.lowercase(Locale.ROOT).let { it.contains("ranchu") || it.contains("goldfish") },
        )
        val context = instrumentation.targetContext
        assertEquals(
            "Revoke local-network permission externally before this test; it does not alter permissions",
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_LOCAL_NETWORK),
        )

        AuthManager.init(context.applicationContext)
        runBlocking { withTimeout(15_000) { AuthManager.awaitInitialization() } }
        assertEquals(AuthManager.InitializationState.Ready, AuthManager.initializationState.value)
        AuthManager.setBaseUrl("http://10.0.2.2:31846/")
        AuthManager.setToken(null)
        AuthManager.setWsAuthParam("ticket")
        AuthManager.setSessionCookie("synthetic")
        assertTrue(AuthManager.isGatedMode())
        assertEquals(null, AuthManager.getToken())
        assertNotNull(AuthManager.getSessionCookie())

        ActivityScenario.launch(MainActivity::class.java).use {
            val deny =
                awaitNode("system local-network permission dialog") { root ->
                    if (!root.packageName.toString().contains("permissioncontroller") ||
                        !containsLocalNetworkText(root)
                    ) {
                        return@awaitNode null
                    }
                    root
                        .findAccessibilityNodeInfosByViewId(
                            "com.android.permissioncontroller:id/permission_deny_button",
                        ).firstOrNull() ?: listOf("Don't allow", "Don’t allow", "Deny").firstNotNullOfOrNull { label ->
                        root.findAccessibilityNodeInfosByText(label).firstOrNull {
                            it.text?.toString() == label
                        }
                    }
                }
            assertTrue("System permission dialog denial must be clickable", clickNodeOrParent(deny))

            val denied = context.getString(R.string.connect_error_lan_permission_denied)
            awaitNode("saved-session LAN denial") { root -> exactText(root, denied) }
            assertFalse("Socket must remain disconnected after denial", HermesWsClient.isConnected)

            val login = context.getString(R.string.auth_login_title)
            val loginButton =
                awaitNode("Auth Login choice") { root ->
                    exactText(root, login)?.takeIf { clickableAncestor(it) != null }
                }
            assertTrue("Auth Login choice must be clickable", clickNodeOrParent(loginButton))
            awaitNode("Auth Login screen") { root ->
                exactText(root, context.getString(R.string.auth_login_desc))
            }
            assertFalse("Choosing login must not connect the socket", HermesWsClient.isConnected)
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_LOCAL_NETWORK),
            )
        }
    }

    private fun exactText(
        root: AccessibilityNodeInfo,
        text: String,
    ): AccessibilityNodeInfo? = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text }

    private fun containsLocalNetworkText(root: AccessibilityNodeInfo): Boolean {
        if (root.text
                ?.toString()
                ?.lowercase(Locale.ROOT)
                ?.contains("local network") == true
        ) {
            return true
        }
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            if (containsLocalNetworkText(child)) return true
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean =
        clickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    private fun awaitNode(
        description: String,
        find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                find(root)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
