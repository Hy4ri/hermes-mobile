package com.m57.hermescontrol.shortcuts

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.SessionInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherShortcutsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun staticActionsResolveToThisBuildWithAccessibleLabels() {
        val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
        val actions = manager.manifestShortcuts.associateBy { it.id }
        for ((id, action, label) in listOf(
            Triple("new_chat", LauncherShortcuts.ACTION_NEW_CHAT, R.string.content_desc_new_chat),
            Triple("history", LauncherShortcuts.ACTION_HISTORY, R.string.screen_history),
        )) {
            val shortcut = requireNotNull(actions[id])
            assertTrue(shortcut.isEnabled)
            assertEquals(context.getString(label), shortcut.shortLabel.toString())
            val intent = requireNotNull(shortcut.intent)
            assertEquals(action, intent.action)
            assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
            assertNotNull(context.packageManager.resolveActivity(intent, 0))
        }
    }

    @Test
    fun coldAndWarmIntentsAreConsumedOnceWithoutReplayingOnRecreation() {
        val scope = DataScope("test", "http://localhost:8080", "default")
        val intent = SessionShortcutPublisher(context).sessionIntent(scope, "chat/one")
        instrumentation.runOnMainSync {
            try {
                LauncherShortcuts.accept(intent)
                val request = requireNotNull(LauncherShortcuts.pendingRequest)
                assertEquals("chat/one", request.sessionId)
                assertEquals(shortcutScopeId(scope), request.scopeId)
                assertEquals(Intent.ACTION_MAIN, intent.action)
                assertFalse(intent.hasExtra(LauncherShortcuts.EXTRA_SESSION))
                LauncherShortcuts.consume(request)
                LauncherShortcuts.accept(intent)
                assertNull(LauncherShortcuts.pendingRequest)
                val warm = Intent(context, MainActivity::class.java).setAction(LauncherShortcuts.ACTION_HISTORY)
                LauncherShortcuts.accept(warm)
                assertEquals(LauncherShortcuts.ACTION_HISTORY, LauncherShortcuts.pendingRequest?.action)
            } finally {
                LauncherShortcuts.pendingRequest?.let(LauncherShortcuts::consume)
            }
        }
    }

    /** Uses system-owned shortcut storage only on an unauthenticated, disposable emulator. */
    @Test
    fun publishesWithinRealQuotaAndRetiresHiddenDeletedAndForeignScopeTargets() {
        assumeTrue(Build.HARDWARE.let { it.contains("ranchu") || it.contains("goldfish") })
        runBlocking { withTimeout(15_000) { AuthManager.awaitInitialization() } }
        assumeTrue(AuthManager.getToken().isNullOrBlank())
        val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
        val publisher = SessionShortcutPublisher(context)
        val scope = DataScope("shortcut-fixture", "http://localhost:8080", "default")
        try {
            val capacity = (manager.maxShortcutCountPerActivity - manager.manifestShortcuts.size).coerceAtLeast(0)
            val rows =
                (0..manager.maxShortcutCountPerActivity).map {
                    SessionInfo("fixture-$it", title = "Conversation $it", last_active = it.toDouble())
                }
            publisher.publish(scope, rows)
            val actual = manager.dynamicShortcuts
            assertEquals(capacity, actual.size)
            assertTrue(
                actual.all { it.intent?.getStringExtra(LauncherShortcuts.EXTRA_SCOPE) == shortcutScopeId(scope) },
            )
            if (capacity > 0) {
                assertEquals("Conversation ${rows.lastIndex}", actual.minBy { it.rank }.shortLabel.toString())
            }
            publisher.publish(scope, listOf(rows.last().copy(hidden = true)))
            assertTrue(manager.dynamicShortcuts.isEmpty())
            publisher.publish(scope.copy(activeProfileId = "other"), listOf(SessionInfo("fixture-0")))
            assertTrue(manager.dynamicShortcuts.none { it.id == sessionShortcutId(scope, "fixture-0") })
            publisher.clear()
            assertTrue(manager.dynamicShortcuts.isEmpty())
            assertEquals(setOf("new_chat", "history"), manager.manifestShortcuts.map { it.id }.toSet())
        } finally {
            publisher.clear()
        }
    }
}
