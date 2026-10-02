package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.RpcMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** Fails CI when the app drifts from the backend's published gateway contract (#1374). */
class GatewayContractTest {
    private val wsMethodConstants: Map<String, String> =
        WsMethods::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to it.get(null) as String }

    @Test
    fun everyWsMethodIsInTheContractOrAllowlisted() {
        val unknown =
            wsMethodConstants.filter { (_, method) ->
                method !in GatewayContract.methods && method !in GatewayContractAllowlist.entries
            }
        assertTrue(
            "WsMethods constants missing from the gateway contract (backend removed or renamed them?): " +
                unknown.entries.joinToString { "${it.key}=\"${it.value}\"" } +
                ". Fix the constant, or allowlist it with a reason in GatewayContractAllowlist.",
            unknown.isEmpty(),
        )
    }

    @Test
    fun profileScopedMethodsAreContractMethods() {
        val unknown = WsMethods.PROFILE_SCOPED_METHODS - GatewayContract.methods
        assertTrue("PROFILE_SCOPED_METHODS not in the contract: $unknown", unknown.isEmpty())
    }

    @Test
    fun allowlistEntriesHaveReasonsAndAreNotStale() {
        val sent = wsMethodConstants.values.toSet()
        GatewayContractAllowlist.entries.forEach { (method, reason) ->
            assertTrue("Allowlist entry '$method' needs a reason", reason.isNotBlank())
            assertTrue(
                "Allowlist entry '$method' is now in the contract; remove it from GatewayContractAllowlist",
                method !in GatewayContract.methods,
            )
            assertTrue(
                "Allowlist entry '$method' is no longer a WsMethods constant; remove it",
                method in sent,
            )
        }
    }

    @Test
    fun handledServerRequestsExistInTheContract() {
        val unknown = APP_HANDLED_SERVER_REQUESTS - GatewayContract.serverRequests
        assertTrue("Handled server requests missing from the contract: $unknown", unknown.isEmpty())

        // Informational: unsupported requests are already answered with -32601.
        val unhandled = (GatewayContract.serverRequests - APP_HANDLED_SERVER_REQUESTS).sorted()
        println("Contract server requests the app does not handle: $unhandled")
    }

    @Test
    fun paramKeyCheckRejectsTheRegressionsItExistsFor() {
        // #1118: the backend forbids extra keys, so since_seq broke replay with error 4000.
        val problems = GatewayContract.paramKeyProblems("session.events.since", setOf("session_id", "since_seq"))
        assertEquals(1, problems.count { "since_seq" in it })
        assertTrue(GatewayContract.paramKeyProblems("session.events.since", setOf("session_id")).isEmpty())
        assertTrue(GatewayContract.paramKeyProblems("session.nope", emptySet()).isNotEmpty())
    }

    @Test
    fun literalParamKeysAtCallSitesMatchTheContract() {
        CALL_SITE_PARAM_KEYS.forEach { (method, keys) ->
            val problems = GatewayContract.paramKeyProblems(method, keys)
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test
    fun typedParamsMatchTheContract() {
        val methods = RpcMethods.all
        assertTrue("RpcMethods.all must not be empty", methods.isNotEmpty())
        val names = methods.map { it.name }
        assertEquals("RpcMethods.all names must be unique", names.distinct(), names)

        val problems =
            methods.flatMap { method ->
                GatewayContract.paramsDescriptorProblems(method.name, method.params.descriptor)
            }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        /** Methods ChatViewModel.handleServerRequest routes; keep in sync with its `when`. */
        val APP_HANDLED_SERVER_REQUESTS =
            setOf("approval", "clarify", "sudo", "secret", "vault.code", "vault.save_login", "vault.unlock_prompt")

        /**
         * Union of literal param keys at production call sites (the two client-owned
         * ones, prompt.submit and session.events.since, are checked against real frames
         * in [GatewayContractFramesTest]). Update together with the call site.
         */
        val CALL_SITE_PARAM_KEYS: Map<String, Set<String>> =
            mapOf(
                // ChatViewModel, GroupChatViewModel
                "session.create" to setOf("profile", "title", "source", "hidden"),
                // ChatViewModel.resumeSession, NotificationReplyReceiver
                "session.resume" to setOf("session_id", "source", "omit_messages"),
                // ChatViewModel, GroupChatViewModel
                "session.interrupt" to setOf("session_id"),
            )
    }
}
