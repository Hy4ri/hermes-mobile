package com.m57.hermescontrol.ui.projects

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.model.ManagedFilesListResponse
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFolderPickerTest {
    private val dispatcher = StandardTestDispatcher()
    private val loader: suspend (path: String?) -> NetworkResult<ManagedFilesListResponse> = mockk()
    private val scopeFlow =
        MutableStateFlow<DataScope?>(
            DataScope(
                connectionProfileId = "conn_1",
                baseUrl = "https://gw.test",
                activeProfileId = "p1",
            ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inline fun <T> withController(
        coroutineScope: CoroutineScope,
        block: (RemoteFolderPickerController) -> T,
    ): T {
        val ctrl =
            RemoteFolderPickerController(
                loader = loader,
                scopes = scopeFlow,
                coroutineScope = coroutineScope,
                ioDispatcher = dispatcher,
            )
        return try {
            block(ctrl)
        } finally {
            ctrl.cancel()
        }
    }

    @Test
    fun initialLoad_filtersDirectoriesOnly() =
        runTest(dispatcher) {
            val response =
                ManagedFilesListResponse(
                    path = "/opt/data",
                    parent = null,
                    root = "/opt/data",
                    entries =
                        listOf(
                            ManagedFileEntry(name = "src", path = "/opt/data/src", isDirectory = true),
                            ManagedFileEntry(name = "notes.txt", path = "/opt/data/notes.txt", isDirectory = false),
                            ManagedFileEntry(name = "build", path = "/opt/data/build", isDirectory = true),
                        ),
                )
            coEvery { loader(null) } returns NetworkResult.Success(response)

            withController(this) { ctrl ->
                ctrl.load(null)
                advanceUntilIdle()

                val state = ctrl.state.value
                assertFalse(state.isLoading)
                assertNull(state.errorMessage)
                assertEquals("/opt/data", state.currentPath)
                assertEquals(2, state.directories.size)
                assertEquals(listOf("src", "build"), state.directories.map { it.name })
            }
        }

    @Test
    fun navigation_openDirectory_loadsChildPath() =
        runTest(dispatcher) {
            val rootResponse =
                ManagedFilesListResponse(
                    path = "/opt/data",
                    parent = null,
                    entries = listOf(ManagedFileEntry(name = "repo", path = "/opt/data/repo", isDirectory = true)),
                )
            val childResponse =
                ManagedFilesListResponse(
                    path = "/opt/data/repo",
                    parent = "/opt/data",
                    entries = listOf(ManagedFileEntry(name = "docs", path = "/opt/data/repo/docs", isDirectory = true)),
                )
            coEvery { loader(null) } returns NetworkResult.Success(rootResponse)
            coEvery { loader("/opt/data/repo") } returns NetworkResult.Success(childResponse)

            withController(this) { ctrl ->
                ctrl.load(null)
                advanceUntilIdle()

                ctrl.openDirectory(ManagedFileEntry(name = "repo", path = "/opt/data/repo", isDirectory = true))
                advanceUntilIdle()

                assertEquals("/opt/data/repo", ctrl.state.value.currentPath)
                assertEquals("/opt/data", ctrl.state.value.parentPath)
                assertEquals(
                    listOf("docs"),
                    ctrl.state.value.directories
                        .map { it.name },
                )
            }
        }

    @Test
    fun navigation_openDirectory_fileEntryIgnored_noNavigation() =
        runTest(dispatcher) {
            val rootResponse =
                ManagedFilesListResponse(
                    path = "/opt/data",
                    parent = null,
                    entries =
                        listOf(
                            ManagedFileEntry(name = "file.txt", path = "/opt/data/file.txt", isDirectory = false),
                        ),
                )
            coEvery { loader(null) } returns NetworkResult.Success(rootResponse)

            withController(this) { ctrl ->
                ctrl.load(null)
                advanceUntilIdle()

                ctrl.openDirectory(
                    ManagedFileEntry(name = "file.txt", path = "/opt/data/file.txt", isDirectory = false),
                )
                advanceUntilIdle()

                // State must remain at /opt/data without invoking loader
                assertEquals("/opt/data", ctrl.state.value.currentPath)
            }
        }

    @Test
    fun navigation_navigateUp_loadsParentPath() =
        runTest(dispatcher) {
            val childResponse =
                ManagedFilesListResponse(
                    path = "/opt/data/repo",
                    parent = "/opt/data",
                    entries = emptyList(),
                )
            val parentResponse =
                ManagedFilesListResponse(
                    path = "/opt/data",
                    parent = null,
                    entries = listOf(ManagedFileEntry(name = "repo", path = "/opt/data/repo", isDirectory = true)),
                )
            coEvery { loader("/opt/data/repo") } returns NetworkResult.Success(childResponse)
            coEvery { loader("/opt/data") } returns NetworkResult.Success(parentResponse)

            withController(this) { ctrl ->
                ctrl.load("/opt/data/repo")
                advanceUntilIdle()

                ctrl.navigateUp()
                advanceUntilIdle()

                assertEquals("/opt/data", ctrl.state.value.currentPath)
                assertNull(ctrl.state.value.parentPath)
            }
        }

    @Test
    fun navigation_navigateUp_noParent_makesNoRequest() =
        runTest(dispatcher) {
            val rootResponse =
                ManagedFilesListResponse(
                    path = "/opt/data",
                    parent = null,
                    entries = emptyList(),
                )
            coEvery { loader(null) } returns NetworkResult.Success(rootResponse)

            withController(this) { ctrl ->
                ctrl.load(null)
                advanceUntilIdle()

                // parent is null -> calling navigateUp must be a no-op
                ctrl.navigateUp()
                advanceUntilIdle()

                assertEquals("/opt/data", ctrl.state.value.currentPath)
                assertNull(ctrl.state.value.parentPath)
            }
        }

    @Test
    fun selectAbsoluteRootAndPath_resolvesSafely_preservesSpacesAndFooDotDotBar() =
        runTest(dispatcher) {
            // Absolute path directly
            val absState =
                RemoteFolderPickerState(
                    currentPath = "/home/user/my project",
                    root = "/home/user",
                    isLoading = false,
                    isConfirmed = true,
                )
            assertEquals("/home/user/my project", absState.resolveCurrentAbsolutePath())
            assertTrue(absState.canSelectCurrent)

            // Relative path safely joined with absolute root
            val relState =
                RemoteFolderPickerState(
                    currentPath = "sub folder/with spaces",
                    root = "/opt/hermes/data",
                    isLoading = false,
                    isConfirmed = true,
                )
            assertEquals("/opt/hermes/data/sub folder/with spaces", relState.resolveCurrentAbsolutePath())
            assertTrue(relState.canSelectCurrent)

            // Absolute root with trailing slash
            val trailingSlashState =
                RemoteFolderPickerState(
                    currentPath = "sub/dir",
                    root = "/opt/hermes/data/",
                    isLoading = false,
                    isConfirmed = true,
                )
            assertEquals("/opt/hermes/data/sub/dir", trailingSlashState.resolveCurrentAbsolutePath())
            assertTrue(trailingSlashState.canSelectCurrent)

            // Path segment with "foo..bar" must be allowed (not traversal)
            val fooDotDotBarState =
                RemoteFolderPickerState(
                    currentPath = "releases/v1.0..beta/artifacts",
                    root = "/opt/data",
                    isLoading = false,
                    isConfirmed = true,
                )
            assertEquals("/opt/data/releases/v1.0..beta/artifacts", fooDotDotBarState.resolveCurrentAbsolutePath())
            assertTrue(fooDotDotBarState.canSelectCurrent)

            // Actual relative path traversal rejected
            val traversalState =
                RemoteFolderPickerState(
                    currentPath = "sub/../dir",
                    root = "/opt/hermes/data",
                    isLoading = false,
                    isConfirmed = true,
                )
            assertNull(traversalState.resolveCurrentAbsolutePath())
            assertFalse(traversalState.canSelectCurrent)
        }

    @Test
    fun rejectRelativePathWithoutAbsoluteRoot() =
        runTest(dispatcher) {
            // Relative path with null root
            val noRootState =
                RemoteFolderPickerState(
                    currentPath = "sub/dir",
                    root = null,
                    isLoading = false,
                    isConfirmed = true,
                )
            assertNull(noRootState.resolveCurrentAbsolutePath())
            assertFalse(noRootState.canSelectCurrent)

            // Relative path with relative root
            val relRootState =
                RemoteFolderPickerState(
                    currentPath = "sub/dir",
                    root = "relative/root",
                    isLoading = false,
                    isConfirmed = true,
                )
            assertNull(relRootState.resolveCurrentAbsolutePath())
            assertFalse(relRootState.canSelectCurrent)
        }

    @Test
    fun selectionBlocked_onInitialUnconfirmed_loading_error_andCancel() =
        runTest(dispatcher) {
            // 1. Initial unconfirmed state (isConfirmed = false)
            val initialState =
                RemoteFolderPickerState(
                    currentPath = "/opt/data",
                    root = "/opt/data",
                    isLoading = false,
                    isConfirmed = false,
                )
            assertFalse(initialState.canSelectCurrent)

            // 2. Loading state
            val loadingState =
                RemoteFolderPickerState(
                    currentPath = "/opt/data",
                    root = "/opt/data",
                    isLoading = true,
                    isConfirmed = true,
                )
            assertFalse(loadingState.canSelectCurrent)

            // 3. Error state
            val errorState =
                RemoteFolderPickerState(
                    currentPath = "/opt/data",
                    root = "/opt/data",
                    isLoading = false,
                    isConfirmed = true,
                    errorMessage = "Network failure",
                )
            assertFalse(errorState.canSelectCurrent)
        }

    @Test
    fun networkError_surfacesErrorMessage_andRetryWorksOnSameFailedChildPath() =
        runTest(dispatcher) {
            val rootResponse =
                ManagedFilesListResponse(
                    path = "/opt/data",
                    parent = null,
                    entries = listOf(ManagedFileEntry(name = "child", path = "/opt/data/child", isDirectory = true)),
                )
            coEvery { loader(null) } returns NetworkResult.Success(rootResponse)
            coEvery { loader("/opt/data/child") } returns
                NetworkResult.Failure(NetworkError.Http(code = 500, message = "Gateway timeout"))

            withController(this) { ctrl ->
                ctrl.load(null)
                advanceUntilIdle()

                // Open child, which fails
                ctrl.openDirectory(ManagedFileEntry(name = "child", path = "/opt/data/child", isDirectory = true))
                advanceUntilIdle()

                val errorState = ctrl.state.value
                assertFalse(errorState.isLoading)
                assertEquals("Gateway timeout", errorState.errorMessage)
                assertFalse(errorState.canSelectCurrent)

                // Retry must retry loading "/opt/data/child", not root
                coEvery { loader("/opt/data/child") } returns
                    NetworkResult.Success(
                        ManagedFilesListResponse(
                            path = "/opt/data/child",
                            parent = "/opt/data",
                            root = "/opt/data",
                            entries = emptyList(),
                        ),
                    )
                ctrl.retry()
                advanceUntilIdle()

                val successState = ctrl.state.value
                assertFalse(successState.isLoading)
                assertNull(successState.errorMessage)
                assertEquals("/opt/data/child", successState.currentPath)
                assertEquals("/opt/data", successState.parentPath)
            }
        }

    @Test
    fun cancel_stopsActiveJobAndPreventsLateMutation() =
        runTest(dispatcher) {
            val deferred = CompletableDeferred<NetworkResult<ManagedFilesListResponse>>()
            coEvery { loader(null) } coAnswers { deferred.await() }

            val ctrl =
                RemoteFolderPickerController(
                    loader = loader,
                    scopes = scopeFlow,
                    coroutineScope = this,
                    ioDispatcher = dispatcher,
                )
            ctrl.load(null)
            advanceTimeBy(10)
            assertTrue(ctrl.state.value.isLoading)

            ctrl.cancel()
            advanceUntilIdle()

            // Complete the deferred after cancel
            deferred.complete(
                NetworkResult.Success(
                    ManagedFilesListResponse(
                        path = "/opt/data",
                        entries = listOf(ManagedFileEntry(name = "late", path = "/opt/data/late", isDirectory = true)),
                    ),
                ),
            )
            advanceUntilIdle()

            // State should not have applied the late response; active load aborted
            assertTrue(
                ctrl.state.value.directories
                    .isEmpty(),
            )
            assertNull(ctrl.selectCurrent())
        }

    @Test
    fun staleNavigation_aborted_ignoresOldResults() =
        runTest(dispatcher) {
            val deferredA = CompletableDeferred<NetworkResult<ManagedFilesListResponse>>()
            val deferredB = CompletableDeferred<NetworkResult<ManagedFilesListResponse>>()

            coEvery { loader("/path/a") } coAnswers { deferredA.await() }
            coEvery { loader("/path/b") } coAnswers { deferredB.await() }

            withController(this) { ctrl ->
                ctrl.load("/path/a")
                advanceTimeBy(10)

                // User navigates to B before A completes
                ctrl.load("/path/b")
                advanceTimeBy(10)

                // A resolves late
                deferredA.complete(
                    NetworkResult.Success(
                        ManagedFilesListResponse(
                            path = "/path/a",
                            entries =
                                listOf(
                                    ManagedFileEntry(name = "from_a", path = "/path/a/dir", isDirectory = true),
                                ),
                        ),
                    ),
                )
                advanceUntilIdle()

                // B resolves
                deferredB.complete(
                    NetworkResult.Success(
                        ManagedFilesListResponse(
                            path = "/path/b",
                            entries =
                                listOf(
                                    ManagedFileEntry(name = "from_b", path = "/path/b/dir", isDirectory = true),
                                ),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Only B's result must be in state
                assertEquals("/path/b", ctrl.state.value.currentPath)
                assertEquals(
                    listOf("from_b"),
                    ctrl.state.value.directories
                        .map { it.name },
                )
            }
        }

    @Test
    fun scopeTransition_synchronousSelectRejection_beforeCollectorRuns() =
        runTest(dispatcher) {
            val initialResponse =
                ManagedFilesListResponse(
                    path = "/opt/data/workspace",
                    root = "/opt/data",
                    entries = emptyList(),
                )
            coEvery { loader(null) } returns NetworkResult.Success(initialResponse)

            withController(this) { ctrl ->
                ctrl.load(null)
                advanceUntilIdle()

                // Confirmed in Scope A
                assertEquals("/opt/data/workspace", ctrl.selectCurrent())

                // Scope shifts synchronously in flow value
                scopeFlow.value =
                    DataScope(
                        connectionProfileId = "conn_2",
                        baseUrl = "https://gw2.test",
                        activeProfileId = "p2",
                    )

                // BEFORE advanceUntilIdle() runs collector: selectCurrent must synchronously refuse selection
                assertNull(ctrl.selectCurrent())

                advanceUntilIdle()
                assertNull(ctrl.selectCurrent())
            }
        }

    @Test
    fun scopeTransition_generationInvalidation_resetsPickerAndDropsStaleResults() =
        runTest(dispatcher) {
            val deferredScopeA = CompletableDeferred<NetworkResult<ManagedFilesListResponse>>()
            coEvery { loader(null) } coAnswers { deferredScopeA.await() }

            withController(this) { ctrl ->
                ctrl.load(null)
                advanceTimeBy(10)
                assertTrue(ctrl.state.value.isLoading)

                // Scope shifts A -> B: StateFlow emission invalidates active job and resets state
                scopeFlow.value =
                    DataScope(
                        connectionProfileId = "conn_2",
                        baseUrl = "https://gw2.test",
                        activeProfileId = "p2",
                    )
                advanceUntilIdle()

                // Scope A resolves late after switch
                deferredScopeA.complete(
                    NetworkResult.Success(
                        ManagedFilesListResponse(
                            path = "/opt/data",
                            entries =
                                listOf(
                                    ManagedFileEntry(name = "p1_dir", path = "/opt/data/p1_dir", isDirectory = true),
                                ),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Late result from Scope A must be discarded, picker reset, selection blocked
                assertTrue(
                    ctrl.state.value.directories
                        .isEmpty(),
                )
                assertFalse(ctrl.state.value.canSelectCurrent)
                assertNull(ctrl.selectCurrent())

                // Scope shifts back B -> A: fresh generation, requires explicit reload, no resurrected state
                scopeFlow.value =
                    DataScope(
                        connectionProfileId = "conn_1",
                        baseUrl = "https://gw.test",
                        activeProfileId = "p1",
                    )
                advanceUntilIdle()

                assertTrue(
                    ctrl.state.value.directories
                        .isEmpty(),
                )
                assertFalse(ctrl.state.value.canSelectCurrent)
            }
        }
}
