package com.m57.hermescontrol.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import retrofit2.Response
import java.io.IOException

sealed interface NetworkResult<out T> {
    data class Success<out T>(
        val data: T,
    ) : NetworkResult<T>

    data class Failure(
        val error: NetworkError,
    ) : NetworkResult<Nothing>
}

sealed interface NetworkError {
    val message: String

    data class Http(
        val code: Int,
        override val message: String,
    ) : NetworkError

    data class Connection(
        override val message: String,
        val cause: IOException,
    ) : NetworkError

    data class AuthExpired(
        override val message: String = "Authentication token has expired. Please log in again.",
    ) : NetworkError

    data class Unknown(
        override val message: String,
        val cause: Throwable,
    ) : NetworkError
}

fun mapHttpError(
    code: Int,
    errorBody: String? = null,
): NetworkError {
    if (code == 401) {
        return NetworkError.AuthExpired()
    }
    // Try to parse FastAPI-style error detail from response body
    val detail =
        if (errorBody != null) {
            try {
                val parsed = com.google.gson.JsonParser.parseString(errorBody).asJsonObject
                parsed.get("detail")?.asString
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

    val message =
        detail ?: when (code) {
            400 -> "Bad Request (HTTP 400): The server could not understand the request."
            403 -> "Forbidden (HTTP 403): You do not have permission to access this resource."
            404 -> "Not Found (HTTP 404): The requested resource could not be found."
            405 -> "Method Not Allowed (HTTP 405)."
            408 -> "Request Timeout (HTTP 408): The server timed out waiting for the request."
            409 -> "Conflict (HTTP 409): The request conflicts with current server state."
            429 -> "Too Many Requests (HTTP 429): Rate limit exceeded."
            in 500..599 -> "Server Error (HTTP $code): The server encountered an error."
            else -> "HTTP Error $code: Unexpected server response."
        }
    return NetworkError.Http(code, message)
}

fun isRetryable(e: IOException): Boolean =
    when (e) {
        is java.net.UnknownHostException -> false
        is javax.net.ssl.SSLException -> false
        is java.net.ConnectException -> true
        is java.net.SocketTimeoutException -> true
        else -> true
    }

fun jitteredBackoff(baseMs: Long): Long {
    val jitter = (baseMs * 0.25 * (Math.random() * 2 - 1)).toLong()
    return baseMs + jitter
}

suspend inline fun <reified T> safeApiCall(
    retries: Int = 2,
    crossinline call: suspend () -> Response<T>,
): NetworkResult<T> {
    var lastException: IOException? = null
    for (attempt in 0..retries) {
        try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                return if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    NetworkResult.Success(null as T)
                }
            } else {
                val code = response.code()
                // Read error body for detail message
                val errorBody =
                    try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                if ((code in 500..599 || code == 429) && attempt < retries) {
                    val backoff = jitteredBackoff(500L * (1 shl attempt))
                    delay(backoff)
                    continue
                }
                return NetworkResult.Failure(mapHttpError(code, errorBody))
            }
        } catch (e: IOException) {
            lastException = e
            if (attempt == retries || !isRetryable(e)) {
                return NetworkResult.Failure(
                    NetworkError.Connection(
                        "Network connection failure: ${e.message ?: "Unknown connection error"}",
                        e,
                    ),
                )
            }
            val backoff = jitteredBackoff(500L * (1 shl attempt))
            delay(backoff)
        } catch (e: Exception) {
            return NetworkResult.Failure(
                NetworkError.Unknown(
                    "An unexpected error occurred: ${e.localizedMessage ?: e.message}",
                    e,
                ),
            )
        }
    }
    return NetworkResult.Failure(
        NetworkError.Connection(
            "Network connection failure after $retries attempts: ${lastException?.message}",
            lastException ?: IOException("Unknown connection error"),
        ),
    )
}
