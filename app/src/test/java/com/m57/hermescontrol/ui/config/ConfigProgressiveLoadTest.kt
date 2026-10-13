package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.SchemaField
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * U1567-config progressive load tests:
 * - Parsed configuration publishes immediately when getConfig succeeds, even if schema/defaults are deferred.
 * - Late schema and defaults hydrate categories and uncovered paths without discarding modified pending edits or active category.
 * - Initial structured load does NOT request getRawConfig (0 calls); toggleYamlMode requests raw config (1 call).
 * - Optional schema/defaults failures (using 400 or documented retries) are non-fatal: usable values remain rendered.
 * - Required getConfig failure with existing data retains old values rather than showing fake empty state.
 * - Cross-scope and overlapping load cancellations do not poison cached metadata or write across scopes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigProgressiveLoadTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    private val scopeA =
        DataScope(
            connectionProfileId = "conn-a",
            baseUrl = "http://192.168.1.50:9119",
            activeProfileId = "main",
            inMemoryAuthGeneration = 1L,
        )
    private val scopeB =
        DataScope(
            connectionProfileId = "conn-b",
            baseUrl = "http://10.0.0.5:9119",
            activeProfileId = "main",
            inMemoryAuthGeneration = 1L,
        )

    private val activeProfileFlow = MutableStateFlow("main")
    private val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val mockConnSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val sampleConfig =
        mapOf<String, JsonElement>(
            "model" to JsonPrimitive("hermes-3"),
            "temperature" to JsonPrimitive(0.7),
        )

    private val sampleSchema =
        ConfigSchemaResponse(
            fields =
                mapOf(
                    "model" to
                        SchemaField(
                            type = "string",
                            description = "Base model",
                            category = "Model Settings",
                        ),
                    "temperature" to
                        SchemaField(
                            type = "number",
                            description = "Sampling temperature",
                            category = "Model Settings",
                        ),
                ),
            category_order = listOf("Model Settings", "Advanced"),
        )

    private val sampleDefaults =
        mapOf<String, JsonElement>(
            "model" to JsonPrimitive("hermes-2"),
            "temperature" to JsonPrimitive(0.5),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockApi = mockk(relaxed = true)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi

        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns scopeA
        every { AuthManager.activeProfileId } returns activeProfileFlow

        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
        every { ProfileSwitchCoordinator.connectionSwitched } returns mockConnSwitchFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `service owner guard uses reference identity instead of Retrofit proxy equality`() =
        runTest {
            // Retrofit forwards Object.equals to its invocation handler, so even self-equality is false.
            // Preserve that real behavior while delegating endpoint calls to the controlled API mock.
            val retrofitService =
                retrofit2.Retrofit
                    .Builder()
                    .baseUrl("http://127.0.0.1:9119/")
                    .callFactory { error("This fixture must not send network requests") }
                    .build()
                    .create(HermesApiService::class.java)
            val service =
                java.lang.reflect.Proxy.newProxyInstance(
                    HermesApiService::class.java.classLoader,
                    arrayOf(HermesApiService::class.java),
                ) { _, method, args ->
                    val target = if (method.declaringClass == Any::class.java) retrofitService else mockApi
                    method.invoke(target, *(args ?: emptyArray()))
                } as HermesApiService
            assertFalse("Fixture must exercise Retrofit's non-reflexive equals", service.equals(service))
            every { ApiClient.hermesApi } returns service
            coEvery { mockApi.getConfig() } returns Response.success(sampleConfig)
            coEvery { mockApi.getConfigSchema() } returns Response.success(sampleSchema)
            coEvery { mockApi.getConfigDefaults() } returns Response.success(sampleDefaults)

            val viewModel = ConfigViewModel(ioDispatcher = testDispatcher)
            advanceUntilIdle()

            assertFalse("A successful response must stop the skeleton loading state", viewModel.uiState.value.isLoading)
            assertEquals(sampleConfig, viewModel.uiState.value.values)
            assertEquals(sampleSchema, viewModel.uiState.value.schema)
        }

    @Test
    fun `getConfig success publishes values and isLoading false immediately while schema and defaults are blocked`() =
        runTest {
            val schemaDeferred = CompletableDeferred<Response<ConfigSchemaResponse>>()
            val defaultsDeferred = CompletableDeferred<Response<Map<String, JsonElement>>>()

            coEvery { mockApi.getConfig() } returns Response.success(sampleConfig)
            coEvery { mockApi.getConfigSchema() } coAnswers { schemaDeferred.await() }
            coEvery { mockApi.getConfigDefaults() } coAnswers { defaultsDeferred.await() }

            val viewModel = ConfigViewModel(ioDispatcher = testDispatcher)
            viewModel.loadAll()

            // Run until the immediate getConfig coroutine completes
            testDispatcher.scheduler.runCurrent()

            val stateEarly = viewModel.uiState.value
            // Early render verification: config values are populated and loading is done
            assertNotNull("Values should be published early", stateEarly.values)
            assertEquals(JsonPrimitive("hermes-3"), stateEarly.values?.get("model"))
            assertFalse("isLoading must be false once required config is available", stateEarly.isLoading)
            assertNull(stateEarly.errorMessage)

            // Release metadata barriers
            schemaDeferred.complete(Response.success(sampleSchema))
            defaultsDeferred.complete(Response.success(sampleDefaults))
            testDispatcher.scheduler.advanceUntilIdle()

            val stateFinal = viewModel.uiState.value
            assertNotNull("Schema must be hydrated", stateFinal.schema)
            assertEquals("Model Settings", stateFinal.schema?.category_order?.first())
            assertNotNull("Defaults must be hydrated", stateFinal.defaults)
            assertEquals(JsonPrimitive("hermes-3"), stateFinal.values?.get("model"))
        }

    @Test
    fun `late metadata does not lose edited values or active category selection`() =
        runTest {
            val schemaDeferred = CompletableDeferred<Response<ConfigSchemaResponse>>()
            val defaultsDeferred = CompletableDeferred<Response<Map<String, JsonElement>>>()

            coEvery { mockApi.getConfig() } returns Response.success(sampleConfig)
            coEvery { mockApi.getConfigSchema() } coAnswers { schemaDeferred.await() }
            coEvery { mockApi.getConfigDefaults() } coAnswers { defaultsDeferred.await() }

            val viewModel = ConfigViewModel(ioDispatcher = testDispatcher)
            viewModel.loadAll()
            testDispatcher.scheduler.runCurrent()

            // User changes a field and switches category before schema arrives
            viewModel.updateField("model", JsonPrimitive("custom-model"))
            viewModel.setActiveCategory("CustomCategory")

            assertEquals(
                JsonPrimitive("custom-model"),
                viewModel.uiState.value.values
                    ?.get("model"),
            )
            assertTrue(
                viewModel.uiState.value.modifiedKeys
                    .contains("model"),
            )
            assertEquals("CustomCategory", viewModel.uiState.value.activeCategory)

            // Hydrate schema and defaults late
            schemaDeferred.complete(Response.success(sampleSchema))
            defaultsDeferred.complete(Response.success(sampleDefaults))
            testDispatcher.scheduler.advanceUntilIdle()

            val stateAfterHydration = viewModel.uiState.value
            // Assert that edited value and modifiedKeys are strictly preserved
            assertEquals(
                "Edited value must not be replaced by late metadata",
                JsonPrimitive("custom-model"),
                stateAfterHydration.values?.get("model"),
            )
            assertTrue(
                "Modified keys set must be preserved across late metadata hydration",
                stateAfterHydration.modifiedKeys.contains("model"),
            )
            assertEquals(
                "User selected category must not be reset across late metadata hydration",
                "CustomCategory",
                stateAfterHydration.activeCategory,
            )
            assertNotNull(stateAfterHydration.schema)
        }

    @Test
    fun `initial structured load performs 0 raw config calls and toggleYamlMode performs 1 call`() =
        runTest {
            coEvery { mockApi.getConfig() } returns Response.success(sampleConfig)
            coEvery { mockApi.getConfigSchema() } returns Response.success(sampleSchema)
            coEvery { mockApi.getConfigDefaults() } returns Response.success(sampleDefaults)
            coEvery { mockApi.getRawConfig() } returns
                Response.success(RawConfigResponse(path = "/etc/hermes.yaml", yaml = "model: hermes-3\n"))

            val viewModel = ConfigViewModel(ioDispatcher = testDispatcher)
            viewModel.loadAll()
            testDispatcher.scheduler.advanceUntilIdle()

            // Initial load must not fetch raw config
            coVerify(exactly = 0) { mockApi.getRawConfig() }
            assertFalse(viewModel.uiState.value.yamlMode)

            // Switch to YAML mode
            viewModel.toggleYamlMode()
            testDispatcher.scheduler.advanceUntilIdle()

            // Must request raw config on demand (exactly 1 call)
            coVerify(exactly = 1) { mockApi.getRawConfig() }
            assertTrue(viewModel.uiState.value.yamlMode)
            assertEquals("model: hermes-3\n", viewModel.uiState.value.yamlText)
            assertEquals("/etc/hermes.yaml", viewModel.uiState.value.path)
        }

    @Test
    fun `optional metadata failure via HTTP 400 is non-fatal and retains parsed config values`() =
        runTest {
            coEvery { mockApi.getConfig() } returns Response.success(sampleConfig)
            // Use 400 Bad Request to avoid safeApiRead retry loop on 5xx/IOException
            coEvery { mockApi.getConfigSchema() } returns
                Response.error(400, "Bad Request: Schema not supported".toResponseBody(null))
            coEvery { mockApi.getConfigDefaults() } returns
                Response.error(400, "Bad Request: Defaults not supported".toResponseBody(null))

            val viewModel = ConfigViewModel(ioDispatcher = testDispatcher)
            viewModel.loadAll()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNotNull("Values must be present despite schema/defaults failure", state.values)
            assertEquals(JsonPrimitive("hermes-3"), state.values?.get("model"))
            assertFalse("Loading must be completed", state.isLoading)
            assertNull("Optional metadata failure must not set fatal error message", state.errorMessage)
            assertNull(state.schema)
            assertNull(state.defaults)
        }

    @Test
    fun `required getConfig failure preserves existing old values and surfaces error`() =
        runTest {
            coEvery { mockApi.getConfig() } returns Response.success(sampleConfig)
            coEvery { mockApi.getConfigSchema() } returns Response.success(sampleSchema)
            coEvery { mockApi.getConfigDefaults() } returns Response.success(sampleDefaults)

            val viewModel = ConfigViewModel(ioDispatcher = testDispatcher)
            viewModel.loadAll()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                JsonPrimitive("hermes-3"),
                viewModel.uiState.value.values
                    ?.get("model"),
            )

            // Subsequent refresh fails with 400
            coEvery { mockApi.getConfig() } returns
                Response.error(400, "Bad Request: Server failed".toResponseBody(null))

            viewModel.loadAll(forceRefresh = true)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            // Must retain old values, not wipe them out into fake empty success
            assertNotNull("Old values must be retained on required config failure", state.values)
            assertEquals(JsonPrimitive("hermes-3"), state.values?.get("model"))
            assertNotNull("Error message should be set on failure", state.errorMessage)
            assertFalse(state.isLoading)
        }

    @Test
    fun `overlapping load or scope switch prevents late responses from poisoning cache or overwriting state`() =
        runTest {
            val configDeferredA = CompletableDeferred<Response<Map<String, JsonElement>>>()
            val schemaDeferredA = CompletableDeferred<Response<ConfigSchemaResponse>>()
            val defaultsDeferredA = CompletableDeferred<Response<Map<String, JsonElement>>>()

            val configB =
                mapOf<String, JsonElement>(
                    "model" to JsonPrimitive("hermes-b"),
                )
            val schemaB =
                ConfigSchemaResponse(
                    fields =
                        mapOf(
                            "model" to SchemaField(type = "string", category = "Scope B"),
                        ),
                    category_order = listOf("Scope B"),
                )

            coEvery { mockApi.getConfig() } coAnswers { configDeferredA.await() }
            coEvery { mockApi.getConfigSchema() } coAnswers { schemaDeferredA.await() }
            coEvery { mockApi.getConfigDefaults() } coAnswers { defaultsDeferredA.await() }

            val viewModel = ConfigViewModel(ioDispatcher = testDispatcher)
            viewModel.loadAll()
            testDispatcher.scheduler.runCurrent()

            // Switch scope to scopeB before scopeA completes
            every { AuthManager.currentDataScope() } returns scopeB
            activeProfileFlow.value = "profile-b"

            // Configure mock API for scope B
            coEvery { mockApi.getConfig() } returns Response.success(configB)
            coEvery { mockApi.getConfigSchema() } returns Response.success(schemaB)
            coEvery { mockApi.getConfigDefaults() } returns Response.success(emptyMap())

            // Start loadAll for scope B
            viewModel.loadAll(forceRefresh = true)
            testDispatcher.scheduler.advanceUntilIdle()

            // State reflects scope B
            assertEquals(
                JsonPrimitive("hermes-b"),
                viewModel.uiState.value.values
                    ?.get("model"),
            )
            assertEquals(
                "Scope B",
                viewModel.uiState.value.schema
                    ?.category_order
                    ?.first(),
            )

            // Now late scope A resolves
            configDeferredA.complete(Response.success(sampleConfig))
            schemaDeferredA.complete(Response.success(sampleSchema))
            defaultsDeferredA.complete(Response.success(sampleDefaults))
            testDispatcher.scheduler.advanceUntilIdle()

            // Scope B state must not be overwritten by late scope A responses
            assertEquals(
                "Scope B values must not be poisoned by late scope A response",
                JsonPrimitive("hermes-b"),
                viewModel.uiState.value.values
                    ?.get("model"),
            )
            assertEquals(
                "Scope B schema must not be poisoned by late scope A response",
                "Scope B",
                viewModel.uiState.value.schema
                    ?.category_order
                    ?.first(),
            )
        }
}
