package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SlashCommandDispatcher(
    private val scope: CoroutineScope,
    private val onInterrupt: () -> Unit,
    private val onNewSession: () -> Unit,
    private val addAssistantMessage: (String) -> Unit,
) {
    fun dispatch(command: String) {
        val parts = command.split(" ")
        val cmd = parts[0].lowercase()

        when (cmd) {
            "/stop", "/interrupt" -> {
                onInterrupt()
            }

            "/new" -> {
                onNewSession()
            }

            "/help" -> {
                val helpText =
                    """
                    **Available Commands:**
                    • `/help` - Show this help menu
                    • `/status` - Check gateway and platform status
                    • `/sessions` - List all chat sessions
                    • `/stats` or `/system` - Check system resource usage
                    • `/new` - Create a new chat session
                    • `/stop` or `/interrupt` - Interrupt the active run
                    """.trimIndent()
                addAssistantMessage(helpText)
            }

            "/status" -> {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getStatus() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
                            val platformsStr =
                                body.gateway_platforms?.entries?.joinToString("\n") { (k, v) ->
                                    "  • **$k**: ${v.state ?: "Unknown"}${if (v.error_code != null) " (Error: ${v.error_code})" else ""}"
                                } ?: "No active platforms"

                            val statusText =
                                """
                                **Hermes Gateway Status:**
                                • **Version:** ${body.version ?: "Unknown"}
                                • **Gateway Running:** ${body.gateway_running ?: false}
                                • **Active Sessions:** ${body.active_sessions ?: 0}
                                • **Auth Required:** ${body.auth_required ?: false}

                                **Platforms:**
                                $platformsStr
                                """.trimIndent()
                            addAssistantMessage(statusText)
                        }

                        is NetworkResult.Failure -> {
                            addAssistantMessage("Failed to retrieve status: ${result.error.message}")
                        }
                    }
                }
            }

            "/sessions" -> {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getSessions() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
                            val sessionsStr =
                                body.sessions.joinToString("\n") { s ->
                                    "• **${s.title ?: "Untitled"}** (ID: `${s.id}`, Messages: ${s.message_count ?: 0})"
                                }
                            addAssistantMessage("**Sessions List:**\n$sessionsStr")
                        }

                        is NetworkResult.Failure -> {
                            addAssistantMessage("Failed to list sessions: ${result.error.message}")
                        }
                    }
                }
            }

            "/stats", "/system" -> {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getSystemStats() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
                            val cpuPct = body.cpuPercent?.let { String.format("%.1f%%", it) } ?: "N/A"
                            val memPct = body.memoryPercent?.let { String.format("%.1f%%", it) } ?: "N/A"
                            val uptimeVal = body.uptime ?: "N/A"
                            val statsText =
                                """
                                **System Resource Stats:**
                                • **CPU Usage:** $cpuPct
                                • **Memory Usage:** $memPct
                                • **Uptime:** $uptimeVal
                                """.trimIndent()
                            addAssistantMessage(statsText)
                        }

                        is NetworkResult.Failure -> {
                            addAssistantMessage("Failed to retrieve system stats: ${result.error.message}")
                        }
                    }
                }
            }

            else -> {
                addAssistantMessage("Unknown command: `$cmd`. Type `/help` to view a list of available commands.")
            }
        }
    }
}
