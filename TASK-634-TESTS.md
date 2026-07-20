# Task: Adapt hermes-mobile unit tests to the ServerEndpoint (baseUrl) API — issue #634

Repo: /opt/hermes-mobile (branch feat/https-server-endpoint-634, already checked out). Do NOT create branches, do NOT open a PR. Edit only files under `app/src/test/`. The production code is DONE and compiles; only the test suite fails to compile against the new API.

## Goal
Make `./gradlew :app:compileDebugUnitTestKotlin` succeed, then run `./gradlew :app:testDebugUnitTest` and make the new + adapted tests pass. Do NOT modify production source (app/src/main).

## The API that changed (read these files first to confirm signatures)
- `app/src/main/java/com/m57/hermescontrol/data/remote/ServerEndpoint.kt`
  - `ServerEndpoint.parse(raw: String, policy: CleartextPolicy = DENY): ServerEndpoint` — throws IllegalArgumentException on bad URL (non-http(s) scheme, embedded creds, query, fragment).
  - `ServerEndpoint.parseForBuild(raw: String): ServerEndpoint` — uses BuildConfig.ALLOW_CLEARTEXT.
  - `ServerEndpoint.fromLegacy(host: String, port: Int): ServerEndpoint` — builds `http://host:port/`.
  - `val baseUrl: HttpUrl`, `val isCleartext: Boolean`, `val securityWarning: String?`, `fun resolve(path): HttpUrl`, `companion DEFAULT_BASE_URL = "https://127.0.0.1:9119/"`.
  - `enum CleartextPolicy { DENY, ALLOW_WITH_WARNING }`.
- `app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt`
  - `getBaseUrl(): String`, `setBaseUrl(baseUrl: String)` (normalizes, clears host/port, sets baseUrl), `endpoint()`, `endpointForBuild()`.
  - `getHost()/getPort()` still exist (derived from parsed base URL) for legacy compatibility.
  - `setHost(host)/setPort(port)` still exist (delegate to setBaseUrl by modifying current endpoint).
  - `baseUrl()` is REMOVED. `wsUrl()` still exists.
- `app/src/main/java/com/m57/hermescontrol/data/remote/ApiClient.kt`
  - `createTempService(baseUrl: String, token: String)` — NOW 2 ARGS (was 3: host, port, token).
- `app/src/main/java/com/m57/hermescontrol/data/remote/CookieManager.kt`
  - `setSessionCookie(rawValue: String?, endpoint: ServerEndpoint)` — 2nd arg is now a `ServerEndpoint`, NOT a host String.
- `app/src/main/java/com/m57/hermescontrol/data/config/ConnectionProfile.kt`
  - Constructor: `ConnectionProfile(name: String, host: String = "127.0.0.1", port: Int = 9119, baseUrl: String? = null)`.
  - Has `val resolvedBaseUrl: String` (falls back to fromLegacy(host,port) when baseUrl is null).
  - Old positional `ConnectionProfile("id","Name","10.0.0.1",8080)` is WRONG — map to `ConnectionProfile(id="id", name="Name", baseUrl="http://10.0.0.1:8080/")`.

## Known compile errors to fix (from the last gradle run)
1. `E2eIntegrationTest.kt:92` — `ApiClient.createTempService(any(), any(), any())` has 3 args; change to 2 (`any()`, `any()`). Also `:1402,:1403,:1428,:1429` reference `profile.host`/`profile.port` — use `profile.resolvedBaseUrl` or build `ServerEndpoint.fromLegacy(host,port).baseUrl.toString()`.
2. `ServerUrlMigrationTest.kt` — `shouldMigrate` is a `suspend fun`; wrap calls in `kotlinx.coroutines.test.runTest { }` (or `runBlocking`). Same for any suspend call.
3. `CookieManagerTest.kt:35,43,46,54,71,81` — `setSessionCookie(value, "hoststring")` must become `setSessionCookie(value, ServerEndpoint.fromLegacy("host", port))` or `ServerEndpoint.parse("https://.../", CleartextPolicy.ALLOW_WITH_WARNING)`.
4. `ServerEndpointTest.kt` — the custom `assertThrows` helper signature is wrong. Replace usages with JUnit's `org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { block }` (standard 2-arg form), OR fix the helper to `inline fun <reified T : Throwable> assertThrows(block: () -> Unit)` and call as `assertThrows<IllegalArgumentException> { ... }`. Pick ONE consistent style.
5. `AuthLoginViewModelTest.kt:55,58,65,68` — `onHostChange`/`onPortChange` and `state.host`/`state.port` no longer exist; use `onBaseUrlChange("https://...")` and `state.baseUrl`. Update any `every { AuthManager.getHost() }` mocks to `every { AuthManager.getBaseUrl() } returns "https://..."`.
6. `ConnectViewModelTest.kt:52` — `createTempService` 3→2 args. `:104,105,123,131,405,406,468,469,491,611,612` — `state.host`/`state.port` → `state.baseUrl`; `onHostChange`/`onPortChange` → `onBaseUrlChange`.
7. `SettingsViewModelTest.kt:228,229` — `onDialogProfileHostChange`/`onDialogProfilePortChange` → `onDialogProfileBaseUrlChange`; `state.dialogProfileHost`/`dialogProfilePort` → `state.dialogProfileBaseUrl`.

## Rules
- MINIMAL changes — keep each test's intent, just fix the API surface so it compiles and the assertions are still meaningful (e.g. assert `resolvedBaseUrl` or `getBaseUrl()` instead of `host:port`).
- Keep using MockK (`every`/`verify`) and JUnit (`@Test`, `assertEquals`, `assertTrue`, `assertThrows`).
- Import `kotlinx.coroutines.test.runTest` if you need a suspend test scope; the project uses MockK + JUnit4 + kotlinx-coroutines-test (check other tests for the import style).
- Do NOT change test assertions to weaken them — if a test asserted `wsUrl()` returned `ws://host:port/...`, assert the equivalent derived from `endpointForBuild().webSocketUrl(...)` or `AuthManager.wsUrl()`.
- ASCII-lexicographic import ordering (uppercase before lowercase), 120 char lines, trailing commas — match the file's existing style.

## Verification (MANDATORY before reporting done)
1. `cd /opt/hermes-mobile && ANDROID_HOME=/opt/android-sdk ./gradlew :app:compileDebugUnitTestKotlin -q 2>&1 | tail -30` — must show NO `e:` errors.
2. `cd /opt/hermes-mobile && ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest --tests 'com.m57.hermescontrol.data.remote.ServerEndpointTest' --tests 'com.m57.hermescontrol.data.config.ServerUrlMigrationTest' 2>&1 | tail -30` — new tests MUST pass.
3. If time permits, run the full `./gradlew :app:testDebugUnitTest` — but NOTE the suite has a KNOWN pre-existing MockK `mockkStatic(Dispatchers::class)` leak that flakes across classes on full runs (not your regression). If the full run fails on an unrelated test due to that leak, note it and rely on the targeted new-test run + compile success. Do NOT loop fixing flakes that aren't yours.
4. Paste real output (tail) for steps 1 and 2.

Report: files changed (list), compile result, new-test run result, any tests you could NOT adapt and why.
