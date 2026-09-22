package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.VaultCodePromptUi
import com.m57.hermescontrol.ui.chat.VaultSaveLoginPromptUi
import com.m57.hermescontrol.ui.chat.VaultUnlockPromptUi
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real lazy layout regression for the custom23 history prepend jump. */
@RunWith(AndroidJUnit4::class)
class FullBleedChatAnchorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingOnly_atExactTop_keepsContentPixel() = checkAnchor(index = 0, offset = 0, prepend = false)

    @Test
    fun loadingOnly_atPartialTop_keepsContentPixel() = checkAnchor(index = 0, offset = 23, prepend = false)

    @Test
    fun prepend_atExactTop_keepsContentPixel() = checkAnchor(index = 0, offset = 0, prepend = true)

    @Test
    fun prepend_atPartialTop_keepsContentPixel() = checkAnchor(index = 0, offset = 23, prepend = true)

    @Test
    fun prepend_atInteriorRow_keepsContentPixel() = checkAnchor(index = 8, offset = 17, prepend = true)

    @Test
    fun prepend_mergingAgentTurn_keepsToolPixel() =
        checkAnchor(index = 0, offset = 13, prepend = true, role = MessageRole.TOOL)

    @Test
    fun loadingOnly_doesNotCancelAnActiveHistoryScroll() =
        checkAnchor(index = 8, offset = 17, prepend = false, holdScroll = true)

    @Test
    fun prepend_usesTheReadersPositionWhenThePageArrives() =
        checkAnchor(index = 0, offset = 23, prepend = true, moveWhileLoading = true)

    @Test
    fun prepend_afterReaderReachesAgentStatus_keepsTailPixel() = checkTailAnchor("agent_status")

    @Test
    fun prepend_afterReaderReachesClarify_keepsTailPixel() = checkTailAnchor("clarify_bubble")

    @Test
    fun prepend_afterReaderReachesVaultUnlock_keepsTailPixel() = checkTailAnchor("vault_unlock_card")

    @Test
    fun prepend_afterReaderReachesVaultSave_keepsTailPixel() = checkTailAnchor("vault_save_login_card")

    @Test
    fun prepend_afterReaderReachesVaultCode_keepsTailPixel() = checkTailAnchor("vault_code_card")

    @Test
    fun prepend_duringActiveScroll_keepsGestureAndLiveTail_thenPublishesAtIdle() =
        checkAnchor(index = 8, offset = 17, prepend = true, holdScroll = true)

    @Test
    fun prepend_deferredDuringScroll_doesNotSurviveSessionSwitch() =
        checkAnchor(index = 8, offset = 17, prepend = true, holdScroll = true, switchSession = true)

    private fun checkTailAnchor(key: String) = checkAnchor(index = 0, offset = 0, prepend = true, tailKey = key)

    private fun checkAnchor(
        index: Int,
        offset: Int,
        prepend: Boolean,
        role: MessageRole = MessageRole.USER,
        holdScroll: Boolean = false,
        moveWhileLoading: Boolean = false,
        tailKey: String? = null,
        switchSession: Boolean = false,
    ) {
        fun message(id: Int) =
            ChatMessage(
                id = "row-$id",
                role = role,
                content = "Message $id\nSecond line\nThird line",
                toolName = if (role == MessageRole.TOOL) "terminal" else null,
                toolStatus = if (role == MessageRole.TOOL) ToolStatus.COMPLETED else null,
            )

        val messages = mutableStateOf((150..189).map(::message))
        val loading = mutableStateOf(false)
        val session = mutableStateOf("session-one")
        val streaming = mutableStateOf(StreamingState())
        val tailKeys =
            listOf("agent_status", "clarify_bubble", "vault_unlock_card", "vault_save_login_card", "vault_code_card")
        val listState = LazyListState(index, offset)
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            val controller = rememberChatScrollController(listState, scope)
            Box(Modifier.size(width = 320.dp, height = if (tailKey == null) 400.dp else 24.dp)) {
                FullBleedChatList(
                    messages = messages.value,
                    streamingState = streaming.value,
                    isAgentTyping = tailKey != null,
                    searchState = ChatSearchState(),
                    typingEffectEnabled = false,
                    typingEffectDelayMs = 30,
                    isLoading = false,
                    isLoadingOlder = loading.value,
                    listState = listState,
                    scrollController = controller,
                    lastAnimatedMessageId = null,
                    onLastAnimatedMessageIdChange = {},
                    viewModel = viewModel,
                    pagingSessionId = session.value,
                    clarifyRequest = if (tailKey != null) ClarifyUi("Choose an option", listOf("Yes")) else null,
                    vaultUnlockPrompt = if (tailKey != null) VaultUnlockPromptUi(null, null) else null,
                    vaultSaveLoginPrompt = if (tailKey != null) VaultSaveLoginPromptUi(null, null) else null,
                    vaultCodePrompt = if (tailKey != null) VaultCodePromptUi(null, null) else null,
                )
            }
        }
        var before =
            compose.runOnIdle {
                listState.layoutInfo.visibleItemsInfo
                    .first { it.index == index }
                    .let { it.key to it.offset }
            }

        fun assertAnchor(stage: String) {
            compose.runOnIdle {
                val after = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == before.first }
                assertNotNull("$stage: anchor ${before.first} left viewport", after)
                assertEquals("$stage: anchor ${before.first} moved (pixels)", before.second, after!!.offset)
            }
        }

        val scrollJob =
            compose.runOnIdle {
                if (holdScroll) {
                    scope.launch {
                        listState.scroll(MutatePriority.UserInput) { awaitCancellation() }
                    }
                } else {
                    null
                }
            }
        compose.runOnIdle {
            if (holdScroll) assertTrue("scroll started", listState.isScrollInProgress)
            loading.value = true
        }
        assertAnchor("loading started")
        if (tailKey != null) {
            compose.runOnIdle { scope.launch { listState.scrollToItem(40 + tailKeys.indexOf(tailKey)) } }
            before =
                compose.runOnIdle {
                    listState.layoutInfo.visibleItemsInfo
                        .first { it.index == listState.firstVisibleItemIndex }
                        .let { it.key to it.offset }
                }
            assertEquals("reader reached requested tail row", tailKey, before.first)
        }
        if (moveWhileLoading) {
            compose.runOnIdle { scope.launch { listState.scrollToItem(index + 3, 19) } }
            before =
                compose.runOnIdle {
                    listState.layoutInfo.visibleItemsInfo
                        .first { it.index == listState.firstVisibleItemIndex }
                        .let { it.key to it.offset }
                }
        }
        compose.runOnIdle {
            if (prepend) messages.value = (0..149).map(::message) + messages.value
            loading.value = false
        }
        assertAnchor(if (prepend) "page prepended" else "loading stopped without data")
        compose.runOnIdle {
            if (holdScroll) {
                assertTrue("loading must not cancel the gesture", scrollJob!!.isActive)
                assertTrue("scroll still in progress", listState.isScrollInProgress)
            }
        }
        if (holdScroll && prepend) {
            compose.runOnIdle {
                assertEquals("older prefix waits for idle", 40, listState.layoutInfo.totalItemsCount)
                messages.value = messages.value + message(190)
                streaming.value =
                    StreamingState(streamingMessage = ChatMessage("stream", MessageRole.ASSISTANT, "Live answer"))
            }
            compose.runOnIdle {
                assertEquals("new settled and streaming tail remain live", 42, listState.layoutInfo.totalItemsCount)
                assertTrue("tail update must not cancel scrolling", scrollJob!!.isActive)
                listState.dispatchRawDelta(18f)
            }
            before =
                compose.runOnIdle {
                    listState.layoutInfo.visibleItemsInfo
                        .first { it.index == listState.firstVisibleItemIndex }
                        .let { it.key to it.offset }
                }
            if (switchSession) {
                compose.runOnIdle {
                    messages.value = (200..239).map(::message)
                    streaming.value = StreamingState()
                    session.value = "session-two"
                }
                compose.runOnIdle {
                    assertEquals(
                        "session changes immediately",
                        40,
                        listState.layoutInfo.totalItemsCount,
                    )
                }
            }
        }
        compose.runOnIdle { scrollJob?.cancel() }
        if (holdScroll && prepend) {
            compose.runOnIdle {
                assertEquals(
                    "no lost or stale pending rows",
                    if (switchSession) 40 else 192,
                    listState.layoutInfo.totalItemsCount,
                )
            }
            if (!switchSession) assertAnchor("gesture ended after reader moved; prefix published")
        }
    }
}
