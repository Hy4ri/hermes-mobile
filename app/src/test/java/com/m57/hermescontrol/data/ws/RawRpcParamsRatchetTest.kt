package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Ratchet guard preventing new raw Map-param request/send call sites for contract RPC methods (#1375).
 *
 * Background:
 * Issue #1375 migrates raw `HermesWsClient.request(...)`/`.send(...)`/wrapper call sites that pass hand-built
 * `Map<String, Any>` params to typed `HermesWsClient.call(RpcMethods.X, params)`.
 *
 * This test asserts that the set/multiset of raw call sites in `app/src/main/java` matches [BASELINE].
 * - Adding a new raw call site fails CI: author must migrate to `HermesWsClient.call(RpcMethods.X, params)` or,
 *   for a not-yet-migrated method, consciously add the entry to [BASELINE].
 * - Migrating a call site to typed `call(...)` makes the baseline entry stale, failing CI until removed.
 *
 * Scanner limitations / blind spots:
 * 1. Indirect wrappers: does not catch wrappers that alias method names through intermediate variables or functions
 *    (e.g. `val m = WsMethods.FOO; send(m)` or helper functions accepting dynamic strings).
 * 2. Raw string literals: does not catch direct string literals bypassing `WsMethods` (e.g. `send("session.list")`).
 * 3. Dynamic dispatch: reflection or computed method identifiers are invisible to the static regex scanner.
 * 4. Custom parameter maps wrapped in separate helper classes or extensions not calling standard request/send.
 */
class RawRpcParamsRatchetTest {
    private val wsMethodConstants: Map<String, String> =
        WsMethods::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to it.get(null) as String }

    @Test
    fun rawRpcCallSitesMatchBaselineRatchet() {
        val rootDir = resolveMainJavaRoot()
        val contractMethodNames = GatewayContract.methods
        val contractConstantNames =
            wsMethodConstants
                .filter { (_, methodStr) -> methodStr in contractMethodNames }
                .keys

        val scannedSites = mutableListOf<String>()
        rootDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains("data/ws/contract") && it.name != "WsMethods.kt" }
            .forEach { file ->
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                val code = stripComments(file.readText())
                val sites = scanRawRpcCalls(code, contractConstantNames)
                for (site in sites) {
                    scannedSites.add("$relativePath::$site")
                }
            }

        scannedSites.sort()

        val staleEntries = BASELINE.filter { it !in scannedSites }
        val newEntries = scannedSites.filter { it !in BASELINE }

        val failureMessage =
            buildString {
                if (newEntries.isNotEmpty()) {
                    appendLine(
                        "raw call added: migrate to HermesWsClient.call(RpcMethods...) or, for a not-yet-migrated method, add to BASELINE:",
                    )
                    newEntries.forEach { appendLine("  + $it") }
                }
                if (staleEntries.isNotEmpty()) {
                    appendLine("stale baseline entry: remove it (call site was migrated or removed):")
                    staleEntries.forEach { appendLine("  - $it") }
                }
            }

        assertEquals(
            failureMessage.trimEnd(),
            BASELINE,
            scannedSites,
        )
    }

    @Test
    fun scannerSelfTestDetectsRawCallsAndIgnoresSafeUsages() {
        val contractConstants = setOf("SESSION_LIST", "SESSION_CREATE", "PROMPT_SUBMIT")

        val sampleCode =
            """
            // Commented call should be ignored:
            // wsClient.request(WsMethods.SESSION_LIST, emptyMap())
            /* Multi-line comment:
               HermesWsClient.send(WsMethods.SESSION_LIST)
            */

            // When branch routing should be ignored:
            when (method) {
                WsMethods.SESSION_LIST -> handleList()
                WsMethods.SESSION_CREATE -> handleCreate()
            }

            // Comparison checks should be ignored:
            if (method == WsMethods.SESSION_LIST) return
            if (method != WsMethods.PROMPT_SUBMIT) return

            // Tracking helpers should be ignored:
            trackRequest(id, WsMethods.SESSION_LIST)
            trackSessionRequest(id, WsMethods.SESSION_CREATE, generation)

            // Positional single-line call:
            HermesWsClient.request(WsMethods.SESSION_LIST, emptyMap())

            // Named argument call across lines:
            request(
                method = WsMethods.SESSION_CREATE,
                params = mapOf("profile" to "test"),
            )

            // Positional multi-line call:
            wsClient.send(
                WsMethods.PROMPT_SUBMIT,
                mapOf("text" to "hello"),
            )
            """.trimIndent()

        val cleaned = stripComments(sampleCode)
        val detected = scanRawRpcCalls(cleaned, contractConstants)

        assertEquals(
            listOf("SESSION_LIST", "SESSION_CREATE", "PROMPT_SUBMIT"),
            detected,
        )
    }

    companion object {
        internal fun resolveMainJavaRoot(): File {
            val userDir = File(System.getProperty("user.dir") ?: ".")
            val candidates =
                listOf(
                    File(userDir, "app/src/main/java/com/m57/hermescontrol"),
                    File(userDir, "src/main/java/com/m57/hermescontrol"),
                    File(userDir, "../app/src/main/java/com/m57/hermescontrol"),
                )
            return candidates.firstOrNull { it.isDirectory }
                ?: error("Could not resolve app/src/main/java/com/m57/hermescontrol from $userDir")
        }

        internal fun stripComments(text: String): String {
            val result = StringBuilder(text.length)
            var i = 0
            val n = text.length
            var inString = false
            var inTripleString = false

            while (i < n) {
                if (!inString && !inTripleString) {
                    if (text.startsWith("\"\"\"", i)) {
                        inTripleString = true
                        result.append("\"\"\"")
                        i += 3
                    } else if (text[i] == '"') {
                        inString = true
                        result.append('"')
                        i++
                    } else if (text.startsWith("//", i)) {
                        var j = text.indexOf('\n', i)
                        if (j == -1) j = n
                        while (i < j) {
                            result.append(' ')
                            i++
                        }
                    } else if (text.startsWith("/*", i)) {
                        var depth = 1
                        var j = i + 2
                        while (j < n && depth > 0) {
                            if (text.startsWith("/*", j)) {
                                depth++
                                j += 2
                            } else if (text.startsWith("*/", j)) {
                                depth--
                                j += 2
                            } else {
                                j++
                            }
                        }
                        while (i < j) {
                            result.append(if (text[i] == '\n') '\n' else ' ')
                            i++
                        }
                    } else {
                        result.append(text[i])
                        i++
                    }
                } else if (inTripleString) {
                    if (text.startsWith("\"\"\"", i)) {
                        inTripleString = false
                        result.append("\"\"\"")
                        i += 3
                    } else {
                        result.append(text[i])
                        i++
                    }
                } else {
                    if (text[i] == '\\' && i + 1 < n) {
                        result.append(text[i]).append(text[i + 1])
                        i += 2
                    } else if (text[i] == '"') {
                        inString = false
                        result.append('"')
                        i++
                    } else {
                        result.append(text[i])
                        i++
                    }
                }
            }
            return result.toString()
        }

        internal fun scanRawRpcCalls(
            cleanCode: String,
            contractConstants: Set<String>,
        ): List<String> {
            val regex = Regex("""WsMethods\.([A-Za-z0-9_]+)""")
            val results = mutableListOf<String>()

            for (match in regex.findAll(cleanCode)) {
                val constantName = match.groupValues[1]
                if (constantName !in contractConstants) continue

                val startIndex = match.range.first
                if (isMethodArgumentOfRpcCall(cleanCode, startIndex)) {
                    results.add(constantName)
                }
            }
            return results
        }

        private fun isMethodArgumentOfRpcCall(
            cleanCode: String,
            matchStart: Int,
        ): Boolean {
            var i = matchStart - 1
            while (i >= 0 && cleanCode[i].isWhitespace()) {
                i--
            }

            var isNamedMethodParam = false
            if (i >= 0 && cleanCode[i] == '=') {
                var j = i - 1
                while (j >= 0 && cleanCode[j].isWhitespace()) {
                    j--
                }
                val identEnd = j + 1
                while (j >= 0 && cleanCode[j].isLetterOrDigit()) {
                    j--
                }
                val paramName = cleanCode.substring(j + 1, identEnd)
                if (paramName == "method") {
                    isNamedMethodParam = true
                    i = j
                    while (i >= 0 && cleanCode[i].isWhitespace()) {
                        i--
                    }
                } else {
                    return false
                }
            }

            if (i < 0) return false
            val delimiter = cleanCode[i]
            if (delimiter != '(' && delimiter != ',') return false
            if (delimiter == ',' && !isNamedMethodParam) {
                // Positional method argument in Kotlin RPC calls is the first argument directly following '('
                return false
            }

            var parenDepth = 0
            var k = i
            while (k >= 0) {
                val c = cleanCode[k]
                if (c == ')') {
                    parenDepth++
                } else if (c == '(') {
                    if (parenDepth > 0) {
                        parenDepth--
                    } else {
                        var m = k - 1
                        while (m >= 0 && cleanCode[m].isWhitespace()) {
                            m--
                        }
                        val endFn = m + 1
                        while (m >= 0 &&
                            (cleanCode[m].isLetterOrDigit() || cleanCode[m] == '.' || cleanCode[m] == '_')
                        ) {
                            m--
                        }
                        val fnName = cleanCode.substring(m + 1, endFn)
                        val lowerFn = fnName.lowercase()
                        if (fnName in setOf("if", "while", "for", "when")) return false
                        if (lowerFn.contains("track")) return false
                        return true
                    }
                } else if (c == '{' || c == '}' || c == ';') {
                    return false
                }
                k--
            }
            return false
        }

        /**
         * Multiset of remaining unmigrated raw RPC call sites in `app/src/main/java/com/m57/hermescontrol/`.
         * Excludes `data/ws/HermesWsClient.kt::SESSION_EVENTS_SINCE` which is migrated to typed `RpcMethods`.
         * Notice HermesWsClient.kt retains its other raw uses: `CLIENT_CAPABILITIES`, `PROMPT_SUBMIT`,
         * and `SESSION_REDIRECT`.
         */
        val BASELINE: List<String> =
            listOf(
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_ACCOUNTS",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_ACCOUNTS_REMOVE",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_CATALOG",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_CONNECT",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_LIST",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_OPERATION_STATUS",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_POLICY_GET",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_POLICY_SET",
                "data/ws/AccountConnectorRepository.kt::CONNECTORS_TOOLS",
                "data/ws/BillingRepository.kt::SUBSCRIPTION_CHANGE",
                "data/ws/BillingRepository.kt::SUBSCRIPTION_PREVIEW",
                "data/ws/BillingRepository.kt::SUBSCRIPTION_RESUME",
                "data/ws/BillingRepository.kt::SUBSCRIPTION_STATE",
                "data/ws/BillingRepository.kt::SUBSCRIPTION_UPGRADE",
                "data/ws/BillingRepository.kt::USAGE_BARS",
                "data/ws/ConnectorRepository.kt::CONNECTORS_CONNECT",
                "data/ws/ConnectorRepository.kt::CONNECTORS_LIST",
                "data/ws/HermesWsClient.kt::CLIENT_CAPABILITIES",
                "data/ws/HermesWsClient.kt::SESSION_REDIRECT",
                "data/ws/ModelOptionsRepository.kt::MODEL_OPTIONS",
                "data/ws/PluginManageRepository.kt::PLUGINS_MANAGE",
                "data/ws/PluginManageRepository.kt::PLUGINS_MANAGE",
                "data/ws/PluginRemovalRepository.kt::PLUGINS_MANAGE",
                "data/ws/ProjectsSource.kt::PROJECTS_LIST",
                "data/ws/SessionLiveStatusSource.kt::SESSION_ACTIVE_LIST",
                "data/ws/SubagentRepository.kt::SUBAGENT_LIST",
                "data/ws/SubagentRepository.kt::SUBAGENT_TAIL",
                "data/ws/VaultRepository.kt::VAULT_LIST",
                "data/ws/VaultRepository.kt::VAULT_LOCK",
                "data/ws/VaultRepository.kt::VAULT_SOURCES",
                "data/ws/VaultRepository.kt::VAULT_SOURCE_SET",
                "data/ws/VaultRepository.kt::VAULT_UNLOCK",
                "ui/bots/BotsViewModel.kt::PROFILES_CONFIGURE",
                "ui/bots/BotsViewModel.kt::PROFILES_CONFIGURE",
                "ui/bots/BotsViewModel.kt::PROFILES_CONFIGURE",
                "ui/bots/BotsViewModel.kt::PROFILES_LIST",
                "ui/bots/group/GroupChatViewModel.kt::PROFILES_CONFIGURE",
                "ui/bots/group/GroupChatViewModel.kt::PROFILES_CONFIGURE",
                "ui/bots/group/GroupChatViewModel.kt::PROFILES_CONFIGURE",
                "ui/bots/group/GroupChatViewModel.kt::PROFILES_LIST",
                "ui/bots/group/GroupChatViewModel.kt::SESSION_INTERRUPT",
                "ui/chat/ChatApprovalsDelegate.kt::APPROVAL_PENDING",
                "ui/chat/ChatApprovalsDelegate.kt::APPROVAL_RECEIVED",
                "ui/chat/ChatApprovalsDelegate.kt::APPROVAL_RESPOND",
                "ui/chat/ChatConnectionOperationDelegate.kt::CONNECTION_RESPOND",
                "ui/chat/ChatConnectionOperationDelegate.kt::CONNECTION_RESPOND",
                "ui/chat/ChatConnectionOperationDelegate.kt::CONNECTORS_OPERATION_WAKE",
                "ui/chat/ChatModelSwitchDelegate.kt::CONFIG_SET",
                "ui/chat/ChatModelSwitchDelegate.kt::CONFIG_SET",
                "ui/chat/ChatModelSwitchDelegate.kt::CONFIG_SET",
                "ui/chat/ChatModelSwitchDelegate.kt::CONFIG_SET",
                "ui/chat/ChatViewModel.kt::COMMANDS_CATALOG",
                "ui/chat/ChatViewModel.kt::COMMAND_DISPATCH",
                "ui/chat/ChatViewModel.kt::COMMAND_DISPATCH",
                "ui/chat/ChatViewModel.kt::CONFIG_GET",
                "ui/chat/ChatViewModel.kt::CONFIG_SET",
                "ui/chat/ChatViewModel.kt::FILE_ATTACH",
                "ui/chat/ChatViewModel.kt::IMAGE_ATTACH_BYTES",
                "ui/chat/ChatViewModel.kt::PROMPT_BTW",
                "ui/chat/ChatViewModel.kt::SESSION_BRANCH",
                "ui/chat/ChatViewModel.kt::SESSION_BRANCH_WHOLE",
                "ui/chat/ChatViewModel.kt::SESSION_COMPRESS",
                "ui/chat/ChatViewModel.kt::SESSION_CONTEXT_BREAKDOWN",
                "ui/chat/ChatViewModel.kt::SESSION_CREATE",
                "ui/chat/ChatViewModel.kt::SESSION_INTERRUPT",
                "ui/chat/ChatViewModel.kt::SESSION_INTERRUPT",
                "ui/chat/ChatViewModel.kt::SESSION_INTERRUPT",
                "ui/chat/ChatViewModel.kt::SESSION_INTERRUPT",
                "ui/chat/ChatViewModel.kt::SESSION_LIST",
                "ui/chat/ChatViewModel.kt::SESSION_RESUME",
                "ui/chat/ChatViewModel.kt::SESSION_STEER",
                "ui/chat/ChatViewModel.kt::SESSION_USAGE",
                "ui/chat/ChatViewModel.kt::SLASH_EXEC",
                "ui/process/ProcessesViewModel.kt::PROCESS_KILL",
                "ui/process/ProcessesViewModel.kt::PROCESS_LIST",
            )
    }
}
