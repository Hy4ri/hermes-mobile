package com.m57.hermescontrol.ui.sessions

import androidx.annotation.StringRes
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.ServerEndpoint

/** No endpoint credentials or mutable backend state is exposed to the UI. */
data class HistoryActionFeedback(
    val id: Long,
    @param:StringRes val messageRes: Int,
    val canUndo: Boolean = false,
)

/** Freeze the endpoint, profile, token, and cookies before the first suspension. */
internal fun createHistoryActionService(owner: DataScope): HermesApiService {
    check(AuthManager.currentDataScope() == owner) { "History owner changed" }
    val endpoint = ServerEndpoint.parseForBuild(owner.baseUrl).baseUrl
    val token = if (AuthManager.isGatedMode()) null else AuthManager.getToken()
    val cookies =
        if (CookieManager.isInitialized()) {
            CookieManager.cookieJar.loadForRequest(requireNotNull(endpoint.resolve("api/sessions")))
        } else {
            emptyList()
        }
    check(AuthManager.currentDataScope() == owner) { "History owner changed" }
    return ApiClient.createScopedService(owner, token, cookies)
}
