package com.m57.hermescontrol.profilelock

import com.m57.hermescontrol.AuthLoginScreen
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.ClientCertificatesScreen
import com.m57.hermescontrol.ConfigScreen
import com.m57.hermescontrol.ConnectionsScreen
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.KanbanTaskDetailKey
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.LogsScreen
import com.m57.hermescontrol.ProfilesScreen
import com.m57.hermescontrol.ProjectsScreen
import com.m57.hermescontrol.ProvidersScreen
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsBehavior
import com.m57.hermescontrol.SettingsChat
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsLanguage
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.SettingsVault
import com.m57.hermescontrol.SkillsScreen
import com.m57.hermescontrol.ToolsetDetailKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileLockTest {
    private val prefs = FakeSharedPreferences()
    private var now = 1_000_000L
    private val target = ProfileLockTarget(connectionProfileId = "home", profileName = "kids")

    @Before
    fun setUp() {
        ProfileLock.clock = { now }
        ProfileLock.setStoreForTest(ProfileLockStore(prefs))
    }

    @After
    fun tearDown() {
        ProfileLock.clock = System::currentTimeMillis
        ProfileLock.setStoreForTest(null)
    }

    @Test
    fun `lock is off by default and allows everything`() {
        assertFalse(ProfileLock.isLocked)
        ScreenRegistry.ALL_SCREENS.forEach { assertTrue(ProfileLock.allows(it.key)) }
        assertTrue(ProfileLock.allowsActiveProfile("anything"))
        assertTrue(ProfileLock.allowsConnection("other"))
        assertTrue(ProfileLock.allowsRpc("config.set", mapOf("scope" to "global")))
    }

    @Test
    fun `enable stores only a salted hash and locks to the target`() {
        assertTrue(ProfileLock.enable(target, "4826"))

        assertEquals(ProfileLockState(locked = true, target = target), ProfileLock.state.value)
        assertFalse("PIN must never be stored", prefs.values.values.any { it.toString().contains("4826") })
        assertTrue(prefs.values[ProfileLockStore.KEY_PIN_HASH].toString().isNotBlank())
        assertTrue(prefs.values[ProfileLockStore.KEY_PIN_SALT].toString().isNotBlank())
    }

    @Test
    fun `locked app keeps only chat history projects and local settings`() {
        ProfileLock.enable(target, "4826")

        val allowed =
            listOf(
                LandingScreen,
                ChatScreen,
                HistoryScreen,
                ProjectsScreen,
                SettingsScreen,
                SettingsAppearance,
                SettingsLanguage,
                SettingsChat,
                SettingsBehavior,
                SettingsAbout,
            )
        allowed.forEach { assertTrue("$it should stay reachable", ProfileLock.allows(it)) }

        val blocked =
            listOf(
                ProfilesScreen,
                BotsScreen,
                SkillsScreen,
                CronJobsScreen,
                ConfigScreen,
                ProvidersScreen,
                LogsScreen,
                SettingsConnection,
                SettingsVault,
                ConnectionsScreen,
                ClientCertificatesScreen,
                AuthLoginScreen,
                ToolsetDetailKey("web"),
                KanbanTaskDetailKey("main", "t_abcd"),
            )
        blocked.forEach { assertFalse("$it should be protected", ProfileLock.allows(it)) }

        val drawer =
            ScreenRegistry.ALL_SCREENS
                .filter {
                    it.drawerSection != null &&
                        ProfileLock.allows(
                            it.key,
                        )
                }.map { it.key }
        assertEquals(listOf(ChatScreen, HistoryScreen, ProjectsScreen, SettingsScreen), drawer)
    }

    @Test
    fun `locked app refuses other profiles, connections and global config writes`() {
        ProfileLock.enable(target, "4826")

        assertTrue(ProfileLock.allowsActiveProfile("kids"))
        assertFalse(ProfileLock.allowsActiveProfile("default"))
        assertFalse(ProfileLock.allowsActiveProfile(null))
        assertTrue(ProfileLock.allowsConnection("home"))
        assertFalse(ProfileLock.allowsConnection("work"))
        assertFalse(ProfileLock.allowsConnection(null))

        assertFalse(ProfileLock.allowsRpc("config.set", mapOf("key" to "reasoning", "scope" to "global")))
        assertTrue(ProfileLock.allowsRpc("config.set", mapOf("key" to "reasoning", "scope" to "session")))
        assertTrue(ProfileLock.allowsRpc("config.set", mapOf("key" to "model", "session_id" to "s1")))
        assertTrue(ProfileLock.allowsRpc("prompt.submit", mapOf("scope" to "global")))
    }

    @Test
    fun `default connection target matches a null selection`() {
        ProfileLock.enable(ProfileLockTarget(null, "kids"), "4826")
        assertTrue(ProfileLock.allowsConnection(null))
        assertTrue(ProfileLock.allowsConnection(""))
        assertFalse(ProfileLock.allowsConnection("home"))
    }

    @Test
    fun `only 4 to 8 digit PINs are accepted`() {
        listOf("123", "123456789", "12a4", "", "12 34").forEach { pin ->
            assertFalse(pin, ProfileLock.enable(target, pin))
        }
        assertFalse(ProfileLock.isLocked)
        assertTrue(ProfileLock.isValidPin("1234"))
        assertTrue(ProfileLock.isValidPin("12345678"))
    }

    @Test
    fun `enable is refused while a lock already exists`() {
        assertTrue(ProfileLock.enable(target, "4826"))
        assertFalse(ProfileLock.enable(target.copy(profileName = "other"), "1111"))
        assertEquals(target, ProfileLock.state.value.target)
    }

    @Test
    fun `wrong PIN keeps the lock and counts down attempts`() {
        ProfileLock.enable(target, "4826")

        assertEquals(ProfileUnlockResult.WrongPin(4), ProfileLock.disable("0000"))
        assertEquals(ProfileUnlockResult.WrongPin(3), ProfileLock.disable("1111"))
        assertTrue(ProfileLock.isLocked)
    }

    @Test
    fun `correct PIN turns the lock off and clears the policy`() {
        ProfileLock.enable(target, "4826")
        ProfileLock.disable("0000")

        assertEquals(ProfileUnlockResult.Unlocked, ProfileLock.disable("4826"))
        assertEquals(ProfileLockState(), ProfileLock.state.value)
        assertEquals(mapOf<String, Any?>(ProfileLockStore.KEY_ENABLED to false), prefs.values)
    }

    @Test
    fun `five wrong PINs start a cooldown that even the correct PIN cannot skip`() {
        ProfileLock.enable(target, "4826")
        repeat(ProfileLock.MAX_ATTEMPTS - 1) { ProfileLock.disable("0000") }

        assertEquals(ProfileUnlockResult.CoolingDown(now + ProfileLock.LOCKOUT_MS), ProfileLock.disable("0000"))
        assertTrue(ProfileLock.disable("4826") is ProfileUnlockResult.CoolingDown)
        assertTrue(ProfileLock.isLocked)

        now += ProfileLock.LOCKOUT_MS
        assertEquals(ProfileUnlockResult.Unlocked, ProfileLock.disable("4826"))
    }

    @Test
    fun `lock and failed attempts survive process restart`() {
        ProfileLock.enable(target, "4826")
        ProfileLock.disable("0000")

        // A new process reads the same file through a fresh store.
        ProfileLock.setStoreForTest(ProfileLockStore(prefs, previouslyStored = true))

        assertEquals(ProfileLockState(locked = true, target = target), ProfileLock.state.value)
        assertEquals(ProfileUnlockResult.WrongPin(3), ProfileLock.disable("1111"))
        assertEquals(ProfileUnlockResult.Unlocked, ProfileLock.disable("4826"))
    }

    @Test
    fun `unreadable policy fails closed and cannot be unlocked`() {
        // Malformed XML decoded as an empty map.
        ProfileLock.setStoreForTest(ProfileLockStore(prefs, previouslyStored = true))
        assertTrue(ProfileLock.isLocked)
        assertNull(ProfileLock.state.value.target)
        assertFalse(ProfileLock.allows(ProfilesScreen))
        assertFalse(ProfileLock.allowsActiveProfile("kids"))
        assertEquals(ProfileUnlockResult.Failed, ProfileLock.disable("4826"))
    }

    @Test
    fun `wrongly typed policy values fail closed`() {
        prefs.values[ProfileLockStore.KEY_ENABLED] = "yes"
        ProfileLock.setStoreForTest(ProfileLockStore(prefs))
        assertTrue(ProfileLock.isLocked)
        assertNull(ProfileLock.state.value.target)
    }

    @Test
    fun `enabled policy missing its hash fails closed`() {
        ProfileLock.enable(target, "4826")
        prefs.values.remove(ProfileLockStore.KEY_PIN_HASH)
        ProfileLock.setStoreForTest(ProfileLockStore(prefs))
        assertTrue(ProfileLock.isLocked)
        assertEquals(ProfileUnlockResult.Failed, ProfileLock.disable("4826"))
    }

    @Test
    fun `failed write never reports a lock as enabled`() {
        prefs.failCommits = true
        assertFalse(ProfileLock.enable(target, "4826"))
        assertFalse(ProfileLock.isLocked)
        // The in-memory map was restored, so a re-read does not see a half-written lock.
        prefs.failCommits = false
        ProfileLock.setStoreForTest(ProfileLockStore(prefs))
        assertFalse(ProfileLock.isLocked)
    }

    @Test
    fun `failed write keeps the lock on after a correct PIN`() {
        ProfileLock.enable(target, "4826")
        prefs.failCommits = true

        assertEquals(ProfileUnlockResult.Failed, ProfileLock.disable("4826"))
        assertTrue(ProfileLock.isLocked)
        prefs.failCommits = false
        ProfileLock.setStoreForTest(ProfileLockStore(prefs))
        assertEquals(ProfileLockState(locked = true, target = target), ProfileLock.state.value)
    }

    @Test
    fun `locked chat keeps session commands and refuses agent-changing ones`() {
        assertTrue(ProfileLock.allowsSlashCommand("/config set model x"))
        ProfileLock.enable(target, "4826")

        listOf(
            "/help",
            "/usage",
            "/title Homework",
            "/retry",
            "/compress",
            "/btw what?",
            "/model gpt-5",
            "/reasoning high",
            "/fast",
        ).forEach { assertTrue(it, ProfileLock.allowsSlashCommand(it)) }
        listOf(
            "/config set model x",
            "/tools disable web",
            "/skills install foo",
            "/cron add",
            "/profile use default",
            "/yolo",
            "/update",
            "/plugins",
            "/model gpt-5 --global",
            "/reasoning high -g",
            "/REASONING high GLOBAL",
        ).forEach { assertFalse(it, ProfileLock.allowsSlashCommand(it)) }
    }
}
